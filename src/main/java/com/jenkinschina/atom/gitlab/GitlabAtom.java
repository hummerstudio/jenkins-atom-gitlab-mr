package com.jenkinschina.atom.gitlab;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Write to a GitLab merge request: a note, an approval, or labels.
 *
 * <p>{@code commit-status} already covers the status half of the gitlab plugin (it speaks GitLab, GitHub and Gitea).
 * What is left is the conversation: "here is the build result", "approved", "label it". Same API, three calls —
 * one atom rather than a plugin per verb, and it also covers the violation-comments plugin that posts analysis
 * results as a note.
 */
public class GitlabAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String baseUrl = context.getParam("baseUrl", "").trim();
        String project = context.getParam("project", "").trim();
        String iid = context.getParam("mrIid", "").trim();
        String action = context.getParam("action", "comment").trim().toLowerCase(Locale.ROOT);
        String comment = context.getParam("comment", "");
        String labels = context.getParam("labels", "").trim();
        String token = context.getSecret("token");
        if (token == null) {
            token = context.getParam("token", "");
        }
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 300));

        if (baseUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "baseUrl is required (e.g. https://gitlab.example.com)");
        }
        if (project.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "project is required (path like group/app, or the numeric id)");
        }
        if (iid.isEmpty() || !iid.matches("\\d+")) {
            return AtomResult.error(ErrorType.USER, 40003, "mrIid must be the merge request number");
        }
        if (token == null || token.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40004, "token is required");
        }

        String root = baseUrl.replaceAll("/+$", "") + "/api/v4/projects/" + encode(project)
                + "/merge_requests/" + iid;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();

        switch (action) {
            case "comment" -> {
                if (comment.isBlank()) {
                    return AtomResult.error(ErrorType.USER, 40005, "comment needs text");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("body", comment);
                Result posted = send(client, "POST", root + "/notes", Json.write(body), token, timeoutSeconds);
                if (!posted.ok()) {
                    return AtomResult.error(posted.type(), 40006, "posting the note failed: " + posted.message());
                }
                String noteId = text(asMap(posted.body()), "id");
                context.log("[gitlab] note " + noteId + " on !" + iid);
                return AtomResult.success()
                        .data("action", "comment")
                        .data("noteId", noteId)
                        .data("mrUrl", text(asMap(posted.body()), "web_url"));
            }
            case "approve" -> {
                Result approved = send(client, "POST", root + "/approve", "{}", token, timeoutSeconds);
                if (!approved.ok()) {
                    // 401 here usually means the token lacks api scope; 404 means the MR or project is not visible
                    String hint = approved.status() == 401 || approved.status() == 403
                            ? " (the token needs the 'api' scope and Developer access)"
                            : approved.status() == 404
                                    ? " (check the project path and the MR number)" : "";
                    return AtomResult.error(approved.type(), 40007,
                            "approving !" + iid + " failed: " + approved.message() + hint);
                }
                context.log("[gitlab] approved !" + iid);
                return AtomResult.success()
                        .data("action", "approve")
                        .data("mrUrl", text(asMap(approved.body()), "web_url"));
            }
            case "labels" -> {
                if (labels.isEmpty()) {
                    return AtomResult.error(ErrorType.USER, 40008, "labels needs at least one label name");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("add_labels", labels);
                Result updated = send(client, "PUT", root, Json.write(body), token, timeoutSeconds);
                if (!updated.ok()) {
                    return AtomResult.error(updated.type(), 40009, "setting labels failed: " + updated.message());
                }
                context.log("[gitlab] labels on !" + iid + ": " + labels);
                return AtomResult.success()
                        .data("action", "labels")
                        .data("labels", labels)
                        .data("mrUrl", text(asMap(updated.body()), "web_url"));
            }
            default -> {
                return AtomResult.error(ErrorType.USER, 40010,
                        "unknown action '" + action + "'; known: comment, approve, labels");
            }
        }
    }

    private static Result send(HttpClient client, String method, String url, String body, String token,
                               int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "jenkins-atom-gitlab")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(response.statusCode(), response.body());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw e;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<?, ?> asMap(String body) {
        try {
            Object parsed = Json.parse(body == null ? "{}" : body);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private record Result(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        ErrorType type() {
            return switch (status) {
                case 400, 401, 403, 404, 409 -> ErrorType.USER;
                default -> ErrorType.THIRD_PART;
            };
        }

        String message() {
            String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            return "HTTP " + status + (oneLine.isEmpty() ? "" : " "
                    + (oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine));
        }
    }
}

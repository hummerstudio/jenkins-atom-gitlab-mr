package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GitLab merge-request atom against a local GitLab-shaped endpoint: the three actions, the encoded project path,
 * the token header, the bodies and the failure paths {@code tools/smoke.sh} checked.
 */
class GitlabMrIT {

    private static HttpServer server;
    private static int port;
    private static final List<String[]> REQUESTS = new ArrayList<>();
    private static volatile int status = 200;

    private Atom atom;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", GitlabMrIT::handle);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String token = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
        synchronized (REQUESTS) {
            REQUESTS.add(new String[] {exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body, token});
        }
        String answer;
        int code;
        if (status != 200) {
            code = status;
            answer = "{\"message\":\"403 Forbidden\"}";
        } else if (exchange.getRequestURI().getPath().endsWith("/notes")) {
            code = 201;
            answer = "{\"id\": 555, \"web_url\": \"http://gitlab.example.com/group/app/-/merge_requests/7\"}";
        } else {
            code = exchange.getRequestMethod().equals("PUT") ? 200 : 201;
            answer = "{\"id\": 99, \"iid\": 7, \"web_url\": \"http://gitlab.example.com/group/app/-/merge_requests/7\"}";
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        synchronized (REQUESTS) {
            REQUESTS.clear();
        }
        status = 200;
    }

    private Atom configured() {
        return atom.input("{\"baseUrl\": \"http://127.0.0.1:" + port + "\", \"project\": \"group/app\", \"mrIid\": \"7\"}")
                .file(".secrets.json", "{\"token\": \"glpat-123\"}");
    }

    private static String[] last() {
        synchronized (REQUESTS) {
            return REQUESTS.get(REQUESTS.size() - 1);
        }
    }

    @Test
    @DisplayName("comment: the project path is encoded and the token travels in PRIVATE-TOKEN")
    void comments() throws Exception {
        AtomResult run = configured().input("comment", "build 42 failed, see the log").run();

        run.assertOk();
        assertEquals("555", run.field("noteId"));
        String[] request = last();
        assertEquals("POST", request[0]);
        assertEquals("/api/v4/projects/group%2Fapp/merge_requests/7/notes", request[1]);
        assertEquals("glpat-123", request[3], "the token must travel in PRIVATE-TOKEN");
        String body = String.valueOf(Json.parseObject(request[2]).get("body"));
        assertTrue(body.startsWith("build 42 failed"), request[2]);
    }

    @Test
    @DisplayName("approve posts to the approve endpoint")
    void approves() throws Exception {
        AtomResult run = configured().input("action", "approve").run();

        run.assertOk();
        assertEquals("/api/v4/projects/group%2Fapp/merge_requests/7/approve", last()[1]);
    }

    @Test
    @DisplayName("labels are added with a PUT on the merge request, add_labels verbatim")
    void labels() throws Exception {
        AtomResult run = configured().input("action", "labels").input("labels", "needs-fix,ci").run();

        run.assertOk();
        String[] request = last();
        assertEquals("PUT", request[0]);
        assertEquals("/api/v4/projects/group%2Fapp/merge_requests/7", request[1]);
        assertEquals("needs-fix,ci", String.valueOf(Json.parseObject(request[2]).get("add_labels")), request[2]);
    }

    @Test
    @DisplayName("403 is a token-scope problem, and the message says so")
    void explainsAFourZeroThree() throws Exception {
        status = 403;
        AtomResult run = configured().input("action", "approve").run();

        run.assertRefused();
        assertEquals("error", run.status());
        assertTrue(run.field("message").contains("api' scope"), "the hint is missing: " + run.field("message"));
        assertEquals("1", run.field("errorType"), "403 should be USER(1)");
    }

    @Test
    @DisplayName("an unknown action and a non-numeric MR number are refused by name")
    void refusesBadInput() throws Exception {
        AtomResult action = configured().input("action", "explode").run();
        action.assertRefused();
        assertEquals("error", action.status());
        assertTrue(action.field("message").contains("known: comment"), action.field("message"));

        AtomResult iid = configured().input("mrIid", "abc").run();
        iid.assertRefused();
        assertEquals("error", iid.status());
    }
}

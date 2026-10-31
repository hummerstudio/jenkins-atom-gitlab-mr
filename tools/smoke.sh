#!/usr/bin/env bash
# Three actions: the URL (project path encoded), the token header, the bodies, and the failure paths.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((26000 + RANDOM % 900))
start() { python3 tools/fake_gitlab.py "$port" "$log" "$@" & server=$!
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done; }
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

run_mr() { # run_mr <secrets-json> [key=value ...]
  local secrets="$1"; shift
  local dir; dir="$(mktemp -d)"
  python3 - "$dir/input.json" "$port" "$@" <<'PY'
import json, sys
path, port = sys.argv[1], sys.argv[2]
doc = {"baseUrl": "http://127.0.0.1:" + port, "project": "group/app", "mrIid": "7"}
for pair in sys.argv[3:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
  printf '%s' "$secrets" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# comment
dir=$(run_mr '{"token": "glpat-123"}' comment="build 42 failed, see the log")
status "$dir" | grep -q success || fail "comment: $(cat "$dir/output.json")"
[ "$(output "$dir" noteId)" = "555" ] || fail "note id: $(cat "$dir/output.json")"
python3 - "$log" <<'PY' || fail "note request: $(tail -1 "$log")"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
note = [l for l in lines if l["path"].endswith("/notes")][-1]
assert note["path"] == "/api/v4/projects/group%2Fapp/merge_requests/7/notes", note["path"]
assert note["token"] == "glpat-123", note["token"]
assert json.loads(note["body"])["body"].startswith("build 42 failed"), note["body"]
PY

# approve
: >"$log"
dir=$(run_mr '{"token": "glpat-123"}' action=approve)
status "$dir" | grep -q success || fail "approve: $(cat "$dir/output.json")"
grep -q '"/api/v4/projects/group%2Fapp/merge_requests/7/approve"' "$log" || fail "approve path: $(tail -1 "$log")"

# labels
: >"$log"
dir=$(run_mr '{"token": "glpat-123"}' action=labels labels="needs-fix,ci")
status "$dir" | grep -q success || fail "labels: $(cat "$dir/output.json")"
python3 - "$log" <<'PY' || fail "labels request: $(tail -1 "$log")"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
put = [l for l in lines if l["method"] == "PUT"][-1]
assert put["path"] == "/api/v4/projects/group%2Fapp/merge_requests/7", put["path"]
assert json.loads(put["body"])["add_labels"] == "needs-fix,ci", put["body"]
PY

# 403 is a token-scope problem and says so
stop; start --status 403
dir=$(run_mr '{"token": "bad"}' action=approve)
status "$dir" | grep -q error || fail "403 must fail"
grep -q "api' scope" "$dir/output.json" || fail "the hint is missing: $(cat "$dir/output.json")"
grep -q '"errorType":1' "$dir/output.json" || fail "403 should be USER(1)"

# bad input
stop; start
dir=$(run_mr '{"token": "t"}' action=explode)
status "$dir" | grep -q error || fail "unknown action must be refused"
grep -q "known: comment" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
dir=$(run_mr '{"token": "t"}' mrIid=abc)
status "$dir" | grep -q error || fail "a non-numeric MR number must be refused"

echo "smoke ok: note, approve, labels, encoded project path, token header, 403 hint, bad input"

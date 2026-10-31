#!/usr/bin/env python3
"""A GitLab-shaped endpoint: notes, approve, labels.

    fake_gitlab.py <port> <log> [--status 403]
"""
import http.server
import json
import sys

port, log_path = int(sys.argv[1]), sys.argv[2]
status = 200
args = sys.argv[3:]
while args:
    if args.pop(0) == "--status":
        status = int(args.pop(0))

MR = {"id": 99, "iid": 7, "web_url": "http://gitlab.example.com/group/app/-/merge_requests/7"}


class Handler(http.server.BaseHTTPRequestHandler):
    def _handle(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else ""
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"method": method, "path": self.path, "body": body,
                                  "token": self.headers.get("PRIVATE-TOKEN")}) + "\n")
        if status != 200:
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b'{"message":"403 Forbidden"}')
            return
        if self.path.endswith("/notes"):
            answer = {"id": 555, "web_url": MR["web_url"]}
            self.send_response(201)
        elif self.path.endswith("/approve"):
            answer = dict(MR)
            self.send_response(201)
        else:
            answer = dict(MR)
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(answer).encode())

    def do_POST(self):  # noqa: N802
        self._handle("POST")

    def do_PUT(self):  # noqa: N802
        self._handle("PUT")

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()

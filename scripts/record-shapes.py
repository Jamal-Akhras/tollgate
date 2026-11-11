"""Relay a client's API calls and record their shape, never their content.

Usage: record-shapes.py <upstream base URL> <output.json> [port]

Point a client at http://127.0.0.1:<port> (default 8099) instead of the
provider. Every request and answer passes through untouched. The output holds
each endpoint's header names, beta headers, query parameter names, and the
shape of each body: field names and value types. Only the values of "type",
"role" and "model" are kept, and schemas and tool arguments are not opened.
"""

import http.client
import json
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

KEPT_VALUES = {"type", "role", "model"}
OPAQUE = {"input_schema", "parameters", "schema", "arguments"}
LISTED_HEADERS = {"anthropic-beta", "anthropic-version", "openai-beta", "content-type", "accept"}
NOT_FORWARDED = {"host", "accept-encoding", "connection", "content-length", "transfer-encoding", "keep-alive"}
NOT_RETURNED = {"connection", "transfer-encoding", "keep-alive"}

recording = {}
lock = threading.Lock()


def shape(value, key=None, parent=""):
    if (key in OPAQUE or key == "input" and parent.endswith("tool_use")) and isinstance(value, (dict, list)):
        return {"opaque": True}
    if isinstance(value, dict):
        kind = value["type"] if isinstance(value.get("type"), str) else ""
        fields = {name: shape(item, name, kind) for name, item in value.items()}
        return {"object:" + kind if kind else "object": fields}
    if isinstance(value, list):
        elements = {}
        for item in value:
            elements = merge(elements, shape(item))
        return {"array": elements}
    if isinstance(value, str):
        return {"string": [value] if key in KEPT_VALUES else True}
    if isinstance(value, bool):
        return {"boolean": True}
    if isinstance(value, (int, float)):
        return {"number": True}
    return {"null": True}


def merge(left, right):
    merged = dict(left)
    for kind, detail in right.items():
        if kind not in merged:
            merged[kind] = detail
        elif kind.startswith("object"):
            fields = dict(merged[kind])
            for name, field in detail.items():
                fields[name] = merge(fields.get(name, {}), field)
            merged[kind] = fields
        elif kind == "array":
            merged[kind] = merge(merged[kind], detail)
        elif isinstance(detail, list) and isinstance(merged[kind], list):
            merged[kind] = sorted(set(merged[kind]) | set(detail))
    return merged


def endpoint(method, path):
    return method + " " + re.sub(r"/(?=[^/]*\d)[^/]{12,}", "/{id}", path)


def listed(name, value):
    if name == "user-agent":
        return value.split()[0]
    if name in ("anthropic-beta", "openai-beta"):
        return [beta.strip() for beta in value.split(",") if beta.strip()]
    return value


class Recorder:
    def __init__(self, key, headers, query, body, content_type):
        self.key = key
        self.request = {
            "headers": {name: True for name in headers},
            "query": {name: True for name, _ in query},
            "body": {},
        }
        for name, value in headers.items():
            if name in LISTED_HEADERS or name == "user-agent":
                found = listed(name, value)
                self.request["headers"][name] = found if isinstance(found, list) else [found]
        if body and "json" in content_type:
            self.request["body"] = shape(json.loads(body))
        self.answer = {"contentType": [], "body": {}, "events": {}}
        self.status = None
        self.pending = b""

    def answered(self, status, content_type):
        self.status = str(status)
        self.answer["contentType"] = [content_type.split(";")[0]]

    def chunk(self, data):
        if self.answer["contentType"] == ["text/event-stream"]:
            self.pending += data
            *lines, self.pending = self.pending.split(b"\n")
            for line in lines:
                self.event(line.strip())
        else:
            self.pending += data

    def event(self, line):
        if not line.startswith(b"data:"):
            return
        try:
            data = json.loads(line[5:])
        except ValueError:
            return
        name = data.get("type", "") if isinstance(data, dict) else ""
        self.answer["events"][name] = merge(self.answer["events"].get(name, {}), shape(data))

    def finish(self, output):
        if self.answer["contentType"] != ["text/event-stream"] and "json" in self.answer["contentType"][0]:
            try:
                self.answer["body"] = shape(json.loads(self.pending))
            except ValueError:
                pass
        with lock:
            entry = recording.setdefault(self.key, {"calls": 0, "request": {}, "answers": {}})
            entry["calls"] += 1
            entry["request"] = combine(entry["request"], self.request)
            entry["answers"][self.status] = combine(entry["answers"].get(self.status, {}), self.answer)
            with open(output, "w", encoding="utf-8", newline="\n") as file:
                json.dump(recording, file, indent=2, sort_keys=True)
                file.write("\n")


def combine(left, right):
    combined = dict(left)
    for part, value in right.items():
        old = combined.get(part)
        if part in ("body",):
            combined[part] = merge(old or {}, value)
        elif part == "events":
            events = dict(old or {})
            for name, event in value.items():
                events[name] = merge(events.get(name, {}), event)
            combined[part] = events
        elif isinstance(value, list):
            combined[part] = sorted(set(old or []) | set(value))
        else:
            fields = dict(old or {})
            for name, item in value.items():
                if isinstance(item, list) and isinstance(fields.get(name), list):
                    fields[name] = sorted(set(fields[name]) | set(item))
                elif name not in fields or isinstance(item, list):
                    fields[name] = item
            combined[part] = fields
    return combined


def handler(upstream, output):
    base = urlsplit(upstream)

    class Relay(BaseHTTPRequestHandler):
        def relay(self):
            target = urlsplit(self.path)
            length = int(self.headers.get("content-length") or 0)
            body = self.rfile.read(length) if length else b""
            headers = {name.lower(): value for name, value in self.headers.items()}
            recorder = Recorder(
                endpoint(self.command, target.path),
                {name: value for name, value in headers.items() if name not in NOT_FORWARDED},
                parse_qsl(target.query, keep_blank_values=True),
                body,
                headers.get("content-type", ""))
            connection = (http.client.HTTPSConnection if base.scheme == "https" else http.client.HTTPConnection)(
                base.netloc, timeout=600)
            forwarded = {name: value for name, value in self.headers.items() if name.lower() not in NOT_FORWARDED}
            forwarded["Host"] = base.netloc
            forwarded["Accept-Encoding"] = "identity"
            if body:
                forwarded["Content-Length"] = str(len(body))
            path = base.path.rstrip("/") + target.path + ("?" + target.query if target.query else "")
            connection.request(self.command, path, body=body or None, headers=forwarded)
            answer = connection.getresponse()
            recorder.answered(answer.status, answer.getheader("content-type", ""))
            self.send_response(answer.status)
            chunked = answer.getheader("transfer-encoding", "").lower() == "chunked"
            for name, value in answer.getheaders():
                if name.lower() in NOT_RETURNED or (chunked and name.lower() == "content-length"):
                    continue
                self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            while data := answer.read1(65536):
                recorder.chunk(data)
                self.wfile.write(data)
                self.wfile.flush()
            connection.close()
            recorder.finish(output)
            print(f"{recorder.key} {answer.status}", file=sys.stderr)

        do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = relay

        def log_message(self, format, *args):
            pass

    return Relay


def main(upstream, output, port="8099"):
    with open(output, "w", encoding="utf-8") as file:
        file.write("{}\n")
    server = ThreadingHTTPServer(("127.0.0.1", int(port)), handler(upstream, output))
    print(f"relaying http://127.0.0.1:{port} to {upstream}, recording to {output}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    main(*sys.argv[1:])

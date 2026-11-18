import hashlib
import json
import os
import secrets
import shutil
import socket
import string
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALPHABET = string.ascii_letters + string.digits

CHAT = {
    "id": "c1",
    "object": "chat.completion",
    "created": 1,
    "model": "qwen3:32b",
    "choices": [
        {
            "index": 0,
            "finish_reason": "stop",
            "message": {"role": "assistant", "content": "Hello from the stub"},
        }
    ],
    "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
}

MESSAGE = {
    "id": "msg_1",
    "type": "message",
    "role": "assistant",
    "model": "claude-sonnet-4-5",
    "content": [{"type": "text", "text": "Hello from the stub"}],
    "stop_reason": "end_turn",
    "stop_sequence": None,
    "usage": {"input_tokens": 5, "output_tokens": 4},
}

CHAT_STREAM = (
    'data: {"id":"c1","model":"qwen3:32b","choices":[{"delta":{"content":"Hello"}}]}\n\n'
    'data: {"id":"c1","model":"qwen3:32b","choices":[{"delta":{"content":" from the stub"}}]}\n\n'
    'data: {"id":"c1","model":"qwen3:32b","choices":[],'
    '"usage":{"prompt_tokens":5,"completion_tokens":4}}\n\n'
    "data: [DONE]\n\n"
)

MESSAGE_STREAM = (
    "event: message_start\n"
    'data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",'
    '"model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}\n\n'
    "event: content_block_start\n"
    'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n'
    "event: content_block_delta\n"
    'data: {"type":"content_block_delta","index":0,'
    '"delta":{"type":"text_delta","text":"Hello"}}\n\n'
    "event: content_block_delta\n"
    'data: {"type":"content_block_delta","index":0,'
    '"delta":{"type":"text_delta","text":" from the stub"}}\n\n'
    "event: content_block_stop\n"
    'data: {"type":"content_block_stop","index":0}\n\n'
    "event: message_delta\n"
    'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},'
    '"usage":{"output_tokens":4}}\n\n'
    "event: message_stop\n"
    'data: {"type":"message_stop"}\n\n'
)


class Stub(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        streaming = body.get("stream") is True
        if self.path == "/v1/chat/completions":
            self.answer(CHAT_STREAM if streaming else json.dumps(CHAT), streaming)
        elif self.path == "/v1/messages":
            self.answer(MESSAGE_STREAM if streaming else json.dumps(MESSAGE), streaming)
        else:
            self.send_error(404)

    def answer(self, text: str, streaming: bool) -> None:
        data = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if streaming else "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        pass


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port: int = probe.getsockname()[1]
        return port


def make_key() -> tuple[str, str]:
    first = "".join(secrets.choice(ALPHABET) for _ in range(12))
    second = "".join(secrets.choice(ALPHABET) for _ in range(43))
    key = f"tg_{first}_{second}"
    return key, hashlib.sha256(key.encode()).hexdigest()


def find_jar() -> str:
    given = os.environ.get("TOLLGATE_JAR")
    if given:
        return given
    skipped = ("sources", "javadoc", "original")
    jars = [
        path
        for path in (ROOT / "tollgate-server" / "target").glob("*.jar")
        if not any(word in path.name for word in skipped)
    ]
    if len(jars) != 1:
        print("Build tollgate-server first", file=sys.stderr)
        sys.exit(2)
    return str(jars[0])


def config(stub_url: str, e2e_hash: str, broke_hash: str, e2e_id: str, broke_id: str) -> str:
    return f"""dataClasses: [public, internal, confidential]
providers:
  local: {{ format: openai, baseUrl: "{stub_url}/v1", region: local }}
  cloud: {{ format: anthropic, baseUrl: "{stub_url}", region: us }}
models:
  qwen3: {{ provider: local, upstream: "qwen3:32b", price: free }}
  claude-sonnet:
    provider: cloud
    upstream: claude-sonnet-4-5
    price: {{ input: 3, output: 15, cacheRead: 0.30, cacheWrite: 3.75 }}
    maxOutputTokens: {{ default: 1024, cap: 4096 }}
tenants:
  e2e:
    models: [qwen3, claude-sonnet]
    dataClass: internal
    showCosts: true
    policy: guarded
  broke:
    models: [claude-sonnet]
    dataClass: internal
    budget: {{ limit: 0, period: total }}
    policy: guarded
policies:
  guarded:
    guards:
      - type: data-class
        regions: {{ public: [us, local], internal: [us, local], confidential: [local] }}
      - {{ type: tool-names, refuse: [run_command] }}
keys:
  - {{ id: {e2e_id}, tenant: e2e, sha256: {e2e_hash} }}
  - {{ id: {broke_id}, tenant: broke, sha256: {broke_hash} }}
audit:
  mode: off
"""


def wait_for_server(server: subprocess.Popen[bytes], address: str, key: str) -> bool:
    deadline = time.monotonic() + 90
    request = urllib.request.Request(
        address + "/v1/models", headers={"Authorization": "Bearer " + key}
    )
    while time.monotonic() < deadline:
        if server.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(request, timeout=2) as answer:
                if answer.status == 200:
                    return True
        except OSError:
            pass
        time.sleep(0.5)
    return False


def main() -> int:
    command = sys.argv[1:]
    if not command:
        print("Usage: serve.py <command> [arguments...]", file=sys.stderr)
        return 2
    jar = find_jar()
    folder = Path(tempfile.mkdtemp(prefix="tollgate-clients-"))
    stub = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    server = None
    try:
        e2e_key, e2e_hash = make_key()
        broke_key, broke_hash = make_key()
        (folder / "tollgate.yaml").write_text(
            config(
                f"http://127.0.0.1:{stub.server_address[1]}",
                e2e_hash,
                broke_hash,
                e2e_key.split("_")[1],
                broke_key.split("_")[1],
            )
        )
        port = free_port()
        env = {
            **os.environ,
            "SERVER_PORT": str(port),
            "TOLLGATE_ADMIN_PORT": str(free_port()),
            "MANAGEMENT_SERVER_PORT": str(free_port()),
        }
        log = folder / "server.log"
        with log.open("wb") as output:
            server = subprocess.Popen(
                ["java", "-jar", jar], cwd=folder, env=env, stdout=output, stderr=subprocess.STDOUT
            )
            address = f"http://127.0.0.1:{port}"
            if not wait_for_server(server, address, e2e_key):
                output.flush()
                print(log.read_text(errors="replace")[-5000:], file=sys.stderr)
                return 1
        env["TOLLGATE_BASE_URL"] = address
        env["TOLLGATE_API_KEY"] = e2e_key
        env["TOLLGATE_E2E_BROKE_KEY"] = broke_key
        return subprocess.run(command, env=env).returncode
    finally:
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=20)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        stub.shutdown()
        stub.server_close()
        shutil.rmtree(folder, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())

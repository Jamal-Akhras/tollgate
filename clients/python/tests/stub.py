import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import TracebackType
from typing import Any

import anthropic
import openai
from typing_extensions import Self

KEY = "tg_abcdefghijkl_secretsecretsecret"

CHAT = {
    "id": "chatcmpl-1",
    "object": "chat.completion",
    "created": 0,
    "model": "qwen3",
    "choices": [
        {"index": 0, "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}
    ],
    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
}

MESSAGE = {
    "id": "msg_1",
    "type": "message",
    "role": "assistant",
    "model": "claude-sonnet",
    "content": [{"type": "text", "text": "hello"}],
    "stop_reason": "end_turn",
    "stop_sequence": None,
    "usage": {"input_tokens": 1, "output_tokens": 1},
}


def chat(client: openai.OpenAI, **kwargs: Any) -> None:
    client.chat.completions.create(
        model="qwen3", messages=[{"role": "user", "content": "hi"}], **kwargs
    )


def message(client: anthropic.Anthropic, **kwargs: Any) -> None:
    client.messages.create(
        model="claude-sonnet", max_tokens=16, messages=[{"role": "user", "content": "hi"}], **kwargs
    )


@dataclass
class Request:
    path: str
    headers: dict[str, str]
    body: bytes


@dataclass
class Stub:
    status: int = 200
    reply_headers: dict[str, str] = field(default_factory=dict)
    reply_body: dict[str, Any] = field(default_factory=dict)
    requests: list[Request] = field(default_factory=list)

    def __enter__(self) -> Self:
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", 0))
                headers = {name.lower(): value for name, value in self.headers.items()}
                stub.requests.append(Request(self.path, headers, self.rfile.read(length)))
                body = json.dumps(stub.reply_body).encode()
                self.send_response(stub.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for name, value in stub.reply_headers.items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args: Any) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self._server.serve_forever, daemon=True).start()
        return self

    def __exit__(
        self,
        kind: type[BaseException] | None,
        error: BaseException | None,
        trace: TracebackType | None,
    ) -> None:
        self._server.shutdown()
        self._server.server_close()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

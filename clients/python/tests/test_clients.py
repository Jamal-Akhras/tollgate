import os
import unittest
from unittest.mock import patch

import anthropic
import openai

import tollgate
from stub import CHAT, KEY, MESSAGE, Stub, chat, message


class OpenAiTest(unittest.TestCase):
    def test_sends_the_chat_to_the_v1_path_with_the_key_as_a_bearer(self) -> None:
        with Stub(reply_body=CHAT) as stub:
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        self.assertEqual(stub.requests[0].path, "/v1/chat/completions")
        self.assertEqual(stub.requests[0].headers["authorization"], f"Bearer {KEY}")

    def test_sends_the_data_class_and_user_on_every_call(self) -> None:
        with Stub(reply_body=CHAT) as stub:
            client = tollgate.openai(stub.url, KEY, data_class="confidential", user="u-1")
            chat(client)
            chat(client)
        for request in stub.requests:
            self.assertEqual(request.headers["x-tollgate-data-class"], "confidential")
            self.assertEqual(request.headers["x-tollgate-user"], "u-1")

    def test_lets_the_callers_default_headers_win(self) -> None:
        with Stub(reply_body=CHAT) as stub:
            client = tollgate.openai(
                stub.url, KEY, data_class="confidential", default_headers={tollgate.USER: "mine"}
            )
            chat(client)
        self.assertEqual(stub.requests[0].headers["x-tollgate-data-class"], "confidential")
        self.assertEqual(stub.requests[0].headers["x-tollgate-user"], "mine")

    def test_sends_the_dry_run_header_given_as_extra_headers(self) -> None:
        with Stub(reply_body=CHAT) as stub:
            chat(
                tollgate.openai(stub.url, KEY, max_retries=0),
                extra_headers=tollgate.headers(dry_run=True),
            )
        self.assertEqual(stub.requests[0].headers["x-tollgate-dry-run"], "true")

    def test_passes_other_options_to_the_vendor_client(self) -> None:
        client = tollgate.openai("http://example.test", KEY, timeout=3.5, max_retries=0)
        self.assertEqual(client.timeout, 3.5)
        self.assertEqual(client.max_retries, 0)

    def test_ignores_the_vendor_key_in_the_environment(self) -> None:
        with Stub(reply_body=CHAT) as stub, patch.dict(os.environ, {"OPENAI_API_KEY": "sk-other"}):
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        self.assertEqual(stub.requests[0].headers["authorization"], f"Bearer {KEY}")

    def test_returns_the_async_client(self) -> None:
        client = tollgate.async_openai("http://example.test", KEY)
        self.assertIsInstance(client, openai.AsyncOpenAI)
        self.assertEqual(str(client.base_url), "http://example.test/v1/")


class AnthropicTest(unittest.TestCase):
    def test_sends_the_message_to_the_anthropic_path_with_the_key_as_a_bearer(self) -> None:
        with Stub(reply_body=MESSAGE) as stub:
            message(tollgate.anthropic(stub.url, KEY, max_retries=0))
        self.assertEqual(stub.requests[0].path, "/anthropic/v1/messages")
        self.assertEqual(stub.requests[0].headers["authorization"], f"Bearer {KEY}")

    def test_sends_the_data_class_and_user_on_every_call(self) -> None:
        with Stub(reply_body=MESSAGE) as stub:
            client = tollgate.anthropic(stub.url, KEY, data_class="confidential", user="u-1")
            message(client)
        self.assertEqual(stub.requests[0].headers["x-tollgate-data-class"], "confidential")
        self.assertEqual(stub.requests[0].headers["x-tollgate-user"], "u-1")

    def test_lets_the_callers_default_headers_win(self) -> None:
        with Stub(reply_body=MESSAGE) as stub:
            client = tollgate.anthropic(
                stub.url, KEY, user="u-1", default_headers={tollgate.USER: "mine"}, max_retries=0
            )
            message(client)
        self.assertEqual(stub.requests[0].headers["x-tollgate-user"], "mine")

    def test_sends_the_dry_run_header_given_as_extra_headers(self) -> None:
        with Stub(reply_body=MESSAGE) as stub:
            message(
                tollgate.anthropic(stub.url, KEY, max_retries=0),
                extra_headers=tollgate.headers(dry_run=True),
            )
        self.assertEqual(stub.requests[0].headers["x-tollgate-dry-run"], "true")

    def test_ignores_the_vendor_credentials_in_the_environment(self) -> None:
        other = {"ANTHROPIC_API_KEY": "sk-other", "ANTHROPIC_AUTH_TOKEN": "token-other"}
        with Stub(reply_body=MESSAGE) as stub, patch.dict(os.environ, other):
            message(tollgate.anthropic(stub.url, KEY, max_retries=0))
        self.assertEqual(stub.requests[0].headers["authorization"], f"Bearer {KEY}")
        self.assertNotIn("x-api-key", stub.requests[0].headers)

    def test_returns_the_async_client(self) -> None:
        client = tollgate.async_anthropic("http://example.test", KEY)
        self.assertIsInstance(client, anthropic.AsyncAnthropic)
        self.assertEqual(str(client.base_url), "http://example.test/anthropic/")


class SettingsTest(unittest.TestCase):
    def test_takes_the_address_from_the_argument_before_the_variable(self) -> None:
        with patch.dict(os.environ, {"TOLLGATE_BASE_URL": "http://from-env.test"}):
            client = tollgate.openai("http://given.test", KEY)
        self.assertEqual(str(client.base_url), "http://given.test/v1/")

    def test_takes_the_address_from_the_variable_before_the_default(self) -> None:
        with patch.dict(os.environ, {"TOLLGATE_BASE_URL": "http://from-env.test"}):
            client = tollgate.openai(api_key=KEY)
        self.assertEqual(str(client.base_url), "http://from-env.test/v1/")

    def test_falls_back_to_localhost_when_no_address_is_given(self) -> None:
        with patch.dict(os.environ, clear=True):
            client = tollgate.anthropic(api_key=KEY)
        self.assertEqual(str(client.base_url), "http://localhost:8080/anthropic/")

    def test_drops_a_trailing_slash_from_the_address(self) -> None:
        client = tollgate.openai("http://given.test/", KEY)
        self.assertEqual(str(client.base_url), "http://given.test/v1/")

    def test_takes_the_key_from_the_variable(self) -> None:
        with Stub(reply_body=CHAT) as stub, patch.dict(os.environ, {"TOLLGATE_API_KEY": KEY}):
            chat(tollgate.openai(stub.url, max_retries=0))
        self.assertEqual(stub.requests[0].headers["authorization"], f"Bearer {KEY}")

    def test_refuses_a_missing_key_without_naming_any_key(self) -> None:
        other = {"OPENAI_API_KEY": "sk-other", "ANTHROPIC_API_KEY": "sk-other"}
        with patch.dict(os.environ, other):
            os.environ.pop("TOLLGATE_API_KEY", None)
            for make in (tollgate.openai, tollgate.anthropic):
                with self.assertRaises(ValueError) as caught:
                    make()
                self.assertEqual(str(caught.exception), "Set TOLLGATE_API_KEY or pass an API key")


class HeadersTest(unittest.TestCase):
    def test_returns_nothing_when_nothing_is_asked_for(self) -> None:
        self.assertEqual(tollgate.headers(), {})
        self.assertEqual(tollgate.headers(data_class="", user=""), {})

    def test_returns_only_the_headers_asked_for(self) -> None:
        self.assertEqual(tollgate.headers(user="u-1"), {"X-Tollgate-User": "u-1"})

    def test_returns_the_dry_run_header_as_true(self) -> None:
        self.assertEqual(tollgate.headers(dry_run=True), {"X-Tollgate-Dry-Run": "true"})

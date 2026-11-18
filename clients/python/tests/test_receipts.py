import unittest

import anthropic
import openai

import tollgate
from stub import CHAT, KEY, MESSAGE, Stub, chat, message

RECEIPT_HEADERS = {
    "X-Tollgate-Trace-Id": "trace-1",
    "X-Tollgate-Cost": "0.0412 USD",
    "X-Tollgate-Cost-Max": "0.1 USD",
    "X-Tollgate-Output-Limited": "256",
}
REFUSAL_HEADERS = {"X-Tollgate-Error": "policy_denied", "X-Tollgate-Trace-Id": "trace-2"}
SLOW_DOWN = {"error": {"message": "slow down"}}


def raw_chat(stub: Stub) -> tollgate.Receipt:
    client = tollgate.openai(stub.url, KEY, max_retries=0)
    raw = client.chat.completions.with_raw_response.create(
        model="qwen3", messages=[{"role": "user", "content": "hi"}]
    )
    return tollgate.receipt(raw)


class ReceiptTest(unittest.TestCase):
    def test_reads_the_headers_of_an_openai_response(self) -> None:
        with Stub(reply_headers=RECEIPT_HEADERS, reply_body=CHAT) as stub:
            found = raw_chat(stub)
        self.assertEqual(found, tollgate.Receipt("trace-1", "0.0412 USD", "0.1 USD", 256))

    def test_reads_the_headers_of_an_anthropic_response(self) -> None:
        with Stub(reply_headers=RECEIPT_HEADERS, reply_body=MESSAGE) as stub:
            client = tollgate.anthropic(stub.url, KEY, max_retries=0)
            raw = client.messages.with_raw_response.create(
                model="claude-sonnet",
                max_tokens=16,
                messages=[{"role": "user", "content": "hi"}],
            )
        found = tollgate.receipt(raw)
        self.assertEqual(found, tollgate.Receipt("trace-1", "0.0412 USD", "0.1 USD", 256))

    def test_leaves_out_what_the_response_does_not_say(self) -> None:
        with Stub(reply_body=CHAT) as stub:
            found = raw_chat(stub)
        self.assertEqual(found, tollgate.Receipt(None, None, None, None))

    def test_leaves_out_an_output_limit_that_is_not_a_number(self) -> None:
        for value in ("many", "+7", "-7"):
            with Stub(reply_headers={"X-Tollgate-Output-Limited": value}, reply_body=CHAT) as stub:
                found = raw_chat(stub)
            self.assertIsNone(found.output_limited, value)


SPENT = {"X-Tollgate-Error": "budget_exceeded", "x-should-retry": "false"}


class RetryTest(unittest.TestCase):
    def test_openai_does_not_retry_a_spent_budget(self) -> None:
        with (
            Stub(429, SPENT, SLOW_DOWN) as stub,
            self.assertRaises(openai.RateLimitError),
        ):
            chat(tollgate.openai(stub.url, KEY))
        self.assertEqual(len(stub.requests), 1)

    def test_anthropic_does_not_retry_a_spent_budget(self) -> None:
        with (
            Stub(429, SPENT, SLOW_DOWN) as stub,
            self.assertRaises(anthropic.RateLimitError),
        ):
            message(tollgate.anthropic(stub.url, KEY))
        self.assertEqual(len(stub.requests), 1)


class RefusalTest(unittest.TestCase):
    def test_reads_a_refusal_from_an_openai_error(self) -> None:
        with (
            Stub(403, REFUSAL_HEADERS, {"error": {"message": "no tools"}}) as stub,
            self.assertRaises(openai.PermissionDeniedError) as caught,
        ):
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        expected = tollgate.Refusal("policy_denied", caught.exception.message, 403, "trace-2", None)
        self.assertEqual(tollgate.refusal(caught.exception), expected)

    def test_reads_a_refusal_from_an_anthropic_error(self) -> None:
        body = {"type": "error", "error": {"type": "permission_error", "message": "no tools"}}
        with (
            Stub(403, REFUSAL_HEADERS, body) as stub,
            self.assertRaises(anthropic.PermissionDeniedError) as caught,
        ):
            message(tollgate.anthropic(stub.url, KEY, max_retries=0))
        expected = tollgate.Refusal("policy_denied", caught.exception.message, 403, "trace-2", None)
        self.assertEqual(tollgate.refusal(caught.exception), expected)

    def test_reads_retry_after_in_seconds(self) -> None:
        headers = {"X-Tollgate-Error": "rate_limited", "Retry-After": "7"}
        with (
            Stub(429, headers, SLOW_DOWN) as stub,
            self.assertRaises(openai.RateLimitError) as caught,
        ):
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        why = tollgate.refusal(caught.exception)
        assert why is not None
        self.assertEqual(why.retry_after, 7)

    def test_leaves_out_retry_after_given_as_a_date(self) -> None:
        date = "Wed, 21 Oct 2026 07:28:00 GMT"
        headers = {"X-Tollgate-Error": "rate_limited", "Retry-After": date}
        with (
            Stub(429, headers, SLOW_DOWN) as stub,
            self.assertRaises(openai.RateLimitError) as caught,
        ):
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        why = tollgate.refusal(caught.exception)
        assert why is not None
        self.assertIsNone(why.retry_after)

    def test_leaves_out_retry_after_with_a_sign(self) -> None:
        for value in ("+7", "-7"):
            headers = {"X-Tollgate-Error": "rate_limited", "Retry-After": value}
            with (
                Stub(429, headers, SLOW_DOWN) as stub,
                self.assertRaises(openai.RateLimitError) as caught,
            ):
                chat(tollgate.openai(stub.url, KEY, max_retries=0))
            why = tollgate.refusal(caught.exception)
            assert why is not None
            self.assertIsNone(why.retry_after, value)

    def test_gives_nothing_for_an_error_without_the_error_header(self) -> None:
        with (
            Stub(500, {}, {"error": {"message": "boom"}}) as stub,
            self.assertRaises(openai.InternalServerError) as caught,
        ):
            chat(tollgate.openai(stub.url, KEY, max_retries=0))
        self.assertIsNone(tollgate.refusal(caught.exception))

    def test_gives_nothing_for_an_error_that_has_no_response(self) -> None:
        self.assertIsNone(tollgate.refusal(ValueError()))

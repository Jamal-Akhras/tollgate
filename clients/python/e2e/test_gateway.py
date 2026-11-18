import os
import unittest

import anthropic
import openai

import tollgate

USER = [{"role": "user", "content": "hi"}]
RUN_COMMAND = {"type": "function", "function": {"name": "run_command", "parameters": {}}}


class GatewayTest(unittest.TestCase):
    def test_answers_a_chat(self) -> None:
        answer = tollgate.openai().chat.completions.create(model="qwen3", messages=USER)

        self.assertEqual(answer.choices[0].message.content, "Hello from the stub")

    def test_answers_a_message(self) -> None:
        answer = tollgate.anthropic().messages.create(
            model="claude-sonnet", max_tokens=16, messages=USER
        )

        self.assertEqual(answer.content[0].text, "Hello from the stub")

    def test_streams_a_chat(self) -> None:
        stream = tollgate.openai().chat.completions.create(
            model="qwen3", messages=USER, stream=True
        )

        text = "".join(chunk.choices[0].delta.content or "" for chunk in stream if chunk.choices)
        self.assertEqual(text, "Hello from the stub")

    def test_streams_a_message(self) -> None:
        with tollgate.anthropic().messages.stream(
            model="claude-sonnet", max_tokens=16, messages=USER
        ) as stream:
            text = "".join(stream.text_stream)

        self.assertEqual(text, "Hello from the stub")

    def test_gives_a_receipt_with_a_trace_id_and_a_cost(self) -> None:
        raw = tollgate.openai().chat.completions.with_raw_response.create(
            model="qwen3", messages=USER
        )

        receipt = tollgate.receipt(raw)
        self.assertTrue(receipt.trace_id)
        self.assertTrue(receipt.cost)

    def test_refuses_a_tool_the_policy_forbids_and_says_why(self) -> None:
        with self.assertRaises(openai.PermissionDeniedError) as caught:
            tollgate.openai().chat.completions.create(
                model="qwen3", messages=USER, tools=[RUN_COMMAND]
            )

        refusal = tollgate.refusal(caught.exception)
        assert refusal is not None
        self.assertEqual(refusal.code, "policy_denied")
        self.assertEqual(refusal.status, 403)
        self.assertTrue(refusal.trace_id)

    def test_refuses_a_spent_budget_and_says_why(self) -> None:
        client = tollgate.anthropic(api_key=os.environ["TOLLGATE_E2E_BROKE_KEY"], max_retries=0)

        with self.assertRaises(anthropic.RateLimitError) as caught:
            client.messages.create(model="claude-sonnet", max_tokens=16, messages=USER)

        refusal = tollgate.refusal(caught.exception)
        assert refusal is not None
        self.assertEqual(refusal.code, "budget_exceeded")

    def test_estimates_a_dry_run(self) -> None:
        raw = tollgate.anthropic().messages.with_raw_response.create(
            model="claude-sonnet",
            max_tokens=16,
            messages=USER,
            extra_headers=tollgate.headers(dry_run=True),
        )

        self.assertTrue(tollgate.receipt(raw).cost_max)
        self.assertEqual(raw.http_response.json()["object"], "tollgate.estimate")

    def test_lists_the_models_of_each_format(self) -> None:
        openai_models = [model.id for model in tollgate.openai().models.list()]
        anthropic_models = [model.id for model in tollgate.anthropic().models.list()]

        self.assertEqual(openai_models, ["qwen3"])
        self.assertEqual(anthropic_models, ["claude-sonnet"])


if __name__ == "__main__":
    unittest.main()

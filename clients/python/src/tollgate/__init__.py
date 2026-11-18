import os
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    import anthropic as anthropic_sdk
    import openai as openai_sdk

DATA_CLASS = "X-Tollgate-Data-Class"
USER = "X-Tollgate-User"
DRY_RUN = "X-Tollgate-Dry-Run"


def _settings(
    path: str,
    base_url: str | None,
    api_key: str | None,
    data_class: str | None,
    user: str | None,
    options: dict[str, Any],
) -> dict[str, Any]:
    address = base_url or os.environ.get("TOLLGATE_BASE_URL") or "http://localhost:8080"
    key = api_key or os.environ.get("TOLLGATE_API_KEY")
    if not key:
        raise ValueError("Set TOLLGATE_API_KEY or pass an API key")
    default_headers = headers(data_class=data_class, user=user)
    default_headers.update(options.pop("default_headers", None) or {})
    return {
        **options,
        "base_url": address.rstrip("/") + path,
        "api_key": key,
        "default_headers": default_headers,
    }


def openai(
    base_url: str | None = None,
    api_key: str | None = None,
    *,
    data_class: str | None = None,
    user: str | None = None,
    **options: Any,
) -> "openai_sdk.OpenAI":
    from openai import OpenAI

    return OpenAI(**_settings("/v1", base_url, api_key, data_class, user, options))


def async_openai(
    base_url: str | None = None,
    api_key: str | None = None,
    *,
    data_class: str | None = None,
    user: str | None = None,
    **options: Any,
) -> "openai_sdk.AsyncOpenAI":
    from openai import AsyncOpenAI

    return AsyncOpenAI(**_settings("/v1", base_url, api_key, data_class, user, options))


def anthropic(
    base_url: str | None = None,
    api_key: str | None = None,
    *,
    data_class: str | None = None,
    user: str | None = None,
    **options: Any,
) -> "anthropic_sdk.Anthropic":
    from anthropic import Anthropic

    return Anthropic(**_settings("/anthropic", base_url, api_key, data_class, user, options))


def async_anthropic(
    base_url: str | None = None,
    api_key: str | None = None,
    *,
    data_class: str | None = None,
    user: str | None = None,
    **options: Any,
) -> "anthropic_sdk.AsyncAnthropic":
    from anthropic import AsyncAnthropic

    return AsyncAnthropic(**_settings("/anthropic", base_url, api_key, data_class, user, options))


def headers(
    *, data_class: str | None = None, user: str | None = None, dry_run: bool = False
) -> dict[str, str]:
    result: dict[str, str] = {}
    if data_class:
        result[DATA_CLASS] = data_class
    if user:
        result[USER] = user
    if dry_run:
        result[DRY_RUN] = "true"
    return result


@dataclass(frozen=True)
class Receipt:
    trace_id: str | None
    cost: str | None
    cost_max: str | None
    output_limited: int | None


@dataclass(frozen=True)
class Refusal:
    code: str
    message: str
    status: int
    trace_id: str | None
    retry_after: int | None


def _number(value: str | None) -> int | None:
    return int(value) if value is not None and value.isascii() and value.isdigit() else None


def receipt(response: Any) -> Receipt:
    found = response.headers
    return Receipt(
        trace_id=found.get("X-Tollgate-Trace-Id"),
        cost=found.get("X-Tollgate-Cost"),
        cost_max=found.get("X-Tollgate-Cost-Max"),
        output_limited=_number(found.get("X-Tollgate-Output-Limited")),
    )


def refusal(error: Any) -> Refusal | None:
    response = getattr(error, "response", None)
    if response is None:
        return None
    found = response.headers
    code = found.get("X-Tollgate-Error")
    if code is None:
        return None
    return Refusal(
        code=code,
        message=error.message,
        status=error.status_code,
        trace_id=found.get("X-Tollgate-Trace-Id"),
        retry_after=_number(found.get("Retry-After")),
    )

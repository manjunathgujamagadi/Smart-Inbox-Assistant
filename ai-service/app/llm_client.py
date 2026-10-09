"""
Thin wrapper around the Azure OpenAI SDK (gpt-5-mini by default; gpt-4o-mini also works).
Structured output is obtained via OpenAI's function-calling / tools feature: we hand the
model a single "tool" whose parameters schema is the exact JSON shape we want, force
tool_choice to that tool, and parse the resulting tool_call's arguments (a JSON string)
into a dict. The model is multimodal, so the same call path handles both plain text and
image content blocks (used for OCR and image description).

Reasoning models (gpt-5*, o-series) differ from gpt-4o-mini in two ways handled here:
they only accept the default temperature, and they take `max_completion_tokens` (which
also has to cover their hidden reasoning tokens) instead of `max_tokens`.
"""
from __future__ import annotations

import base64
import json
from typing import Any

from openai import AzureOpenAI

from app.config import get_settings

_client: AzureOpenAI | None = None


def get_client() -> AzureOpenAI:
    global _client
    if _client is None:
        settings = get_settings()
        _client = AzureOpenAI(
            api_key=settings.azure_openai_api_key,
            azure_endpoint=settings.azure_openai_endpoint,
            api_version=settings.azure_openai_api_version,
        )
    return _client


def _generation_kwargs(max_tokens: int | None, temperature: float | None) -> dict[str, Any]:
    settings = get_settings()
    if settings.is_reasoning_model:
        kwargs: dict[str, Any] = {
            "max_completion_tokens": max(max_tokens or 0, settings.reasoning_output_tokens),
        }
        if settings.azure_openai_reasoning_effort:
            kwargs["reasoning_effort"] = settings.azure_openai_reasoning_effort
        return kwargs  # temperature is fixed at the model default
    kwargs = {"max_completion_tokens": max_tokens or settings.max_output_tokens}
    if temperature is not None:
        kwargs["temperature"] = temperature
    return kwargs


def _truncation_hint(response) -> str:
    reason = response.choices[0].finish_reason
    return " (output was cut off - raise REASONING_OUTPUT_TOKENS)" if reason == "length" else ""


def _build_content(user_text: str, images_b64: list[str] | None) -> list[dict[str, Any]]:
    content: list[dict[str, Any]] = [{"type": "text", "text": user_text}]
    if images_b64:
        for img in images_b64:
            content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/png;base64,{img}"},
            })
    return content


def call_tool(
    *,
    model: str,
    system: str,
    user_text: str,
    tool_name: str,
    tool_description: str,
    input_schema: dict[str, Any],
    images_b64: list[str] | None = None,
    max_tokens: int | None = None,
    temperature: float | None = None,
) -> dict[str, Any]:
    """Calls the model with a single forced tool and returns its parsed arguments dict."""
    response = get_client().chat.completions.create(
        model=model,
        **_generation_kwargs(max_tokens, temperature),
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": _build_content(user_text, images_b64)},
        ],
        tools=[{
            "type": "function",
            "function": {
                "name": tool_name,
                "description": tool_description,
                "parameters": input_schema,
                # Without strict mode, function-calling only *usually*
                # honors the schema's `required` list - it will sometimes omit a
                # field it judges "not applicable" (e.g. sourceType on a narrative
                # field the model wrote itself), which breaks strict parsing on our
                # side. Strict mode guarantees every `required` field is present;
                # callers make anything conceptually optional nullable instead.
                "strict": True,
            },
        }],
        tool_choice={"type": "function", "function": {"name": tool_name}},
    )

    message = response.choices[0].message
    if message.tool_calls:
        return json.loads(message.tool_calls[0].function.arguments)
    raise RuntimeError(f"Model did not return a tool call for '{tool_name}'{_truncation_hint(response)}")


def call_text(*, model: str, system: str, user_text: str, images_b64: list[str] | None = None,
              max_tokens: int | None = None) -> str:
    """Plain-text completion (no forced structured output) - used for summaries/translation."""
    response = get_client().chat.completions.create(
        model=model,
        **_generation_kwargs(max_tokens, None),
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": _build_content(user_text, images_b64)},
        ],
    )
    text = (response.choices[0].message.content or "").strip()
    if not text:
        raise RuntimeError(f"Model returned an empty completion{_truncation_hint(response)}")
    return text


def png_bytes_to_b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")

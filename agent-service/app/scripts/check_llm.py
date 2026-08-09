from __future__ import annotations

import argparse
import asyncio
import json

from core.config import settings
from service.llm.client import _reset_llm_client_for_tests, get_llm_client


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one real LLM JSON request.")
    parser.add_argument(
        "--provider",
        choices=["doubao", "openai"],
        default="doubao",
        help="provider to bind into research slot for this check",
    )
    return parser.parse_args()


async def _run(provider: str) -> None:
    original_provider = settings.LLM_PROVIDER_RESEARCH
    original_model = settings.LLM_MODEL_RESEARCH

    try:
        settings.LLM_PROVIDER_RESEARCH = provider
        settings.LLM_MODEL_RESEARCH = None
        _reset_llm_client_for_tests()

        response = await get_llm_client().complete_json(
            model_slot="research",
            system_prompt="You are a planner. Return strict JSON with keys: chosen_tool, tool_args, reasoning_summary.",
            user_prompt="Decide one next action for a run comparing cursor and windsurf.",
        )
    finally:
        settings.LLM_PROVIDER_RESEARCH = original_provider
        settings.LLM_MODEL_RESEARCH = original_model
        _reset_llm_client_for_tests()

    print(f"provider: {response.provider}")
    print(f"model_name: {response.model_name}")
    print(f"latency_ms: {response.latency_ms}")
    print(f"prompt_tokens: {response.prompt_tokens}")
    print(f"completion_tokens: {response.completion_tokens}")
    print(f"error: {response.error}")
    print(f"content: {json.dumps(response.content, ensure_ascii=False)}")

    if response.error is not None:
        raise RuntimeError("LLM check failed. See error field above.")


def main() -> None:
    args = _parse_args()
    asyncio.run(_run(args.provider))


if __name__ == "__main__":
    main()

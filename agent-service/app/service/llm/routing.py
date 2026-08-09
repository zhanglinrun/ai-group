from __future__ import annotations

from typing import Mapping

from core.config import settings
from service.llm.exceptions import LLMRequestError
from service.llm.providers import LLMProvider

SLOT_NAMES: tuple[str, ...] = (
    "research",
    "summarization",
    "compression",
    "qa",
    "writer",
)
MODEL_TIERS: tuple[str, ...] = ("strong", "balanced", "fast")
PROVIDER_NAMES: tuple[str, ...] = ("doubao", "openai", "qwen")


def _clean_optional_string(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned or None


def _resolve_provider(slot: str) -> str:
    provider_raw = getattr(settings, f"LLM_PROVIDER_{slot.upper()}", None)
    if provider_raw is not None and not isinstance(provider_raw, str):
        raise LLMRequestError(f"Provider override for model_slot={slot} must be a string.")
    provider = (_clean_optional_string(provider_raw) or settings.LLM_ACTIVE_PROVIDER).lower()
    if provider not in PROVIDER_NAMES:
        raise LLMRequestError(
            f"Provider `{provider}` for model_slot={slot} is unsupported. "
            f"Expected one of: {', '.join(PROVIDER_NAMES)}."
        )
    return provider


def _slot_model_override(slot: str) -> str | None:
    model_raw = getattr(settings, f"LLM_MODEL_{slot.upper()}", None)
    if model_raw is None:
        return None
    if not isinstance(model_raw, str):
        raise LLMRequestError(f"Model override for model_slot={slot} must be a string.")

    model = model_raw.strip()
    return model or None


def _resolve_tier(slot: str) -> str:
    tier_raw = getattr(settings, f"LLM_TIER_{slot.upper()}", None)
    if not isinstance(tier_raw, str):
        raise LLMRequestError(f"Tier for model_slot={slot} is not configured.")
    tier = tier_raw.strip().lower()
    if tier not in MODEL_TIERS:
        raise LLMRequestError(
            f"Tier `{tier}` for model_slot={slot} is unsupported. "
            f"Expected one of: {', '.join(MODEL_TIERS)}."
        )
    return tier


def _resolve_catalog_model(provider_name: str, tier: str) -> str | None:
    model_raw = getattr(settings, f"{provider_name.upper()}_MODEL_{tier.upper()}", None)
    if model_raw is not None and not isinstance(model_raw, str):
        raise LLMRequestError(
            f"Catalog model for provider={provider_name}, tier={tier} must be a string."
        )
    return _clean_optional_string(model_raw)


def resolve_slot(*, slot: str, providers: Mapping[str, LLMProvider]) -> tuple[str, str]:
    if slot not in SLOT_NAMES:
        raise LLMRequestError(
            f"Unsupported model_slot={slot}. Expected one of: {', '.join(SLOT_NAMES)}."
        )

    provider_name = _resolve_provider(slot)
    provider = providers.get(provider_name)
    if provider is None:
        raise LLMRequestError(
            f"Provider `{provider_name}` for model_slot={slot} is not initialized."
        )

    tier = _resolve_tier(slot)
    model_name = _slot_model_override(slot) or _resolve_catalog_model(
        provider_name,
        tier,
    ) or _clean_optional_string(provider.default_model)
    if not model_name:
        raise LLMRequestError(
            f"Model for model_slot={slot}, provider={provider_name}, tier={tier} is empty."
        )
    return provider_name, model_name

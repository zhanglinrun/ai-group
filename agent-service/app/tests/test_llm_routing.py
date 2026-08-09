from __future__ import annotations

from dataclasses import dataclass

import pytest

from core.config import settings
from service.llm.exceptions import LLMRequestError
from service.llm.routing import resolve_slot


@dataclass
class _DummyProvider:
    default_model: str


def _reset_routing_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "LLM_ACTIVE_PROVIDER", "doubao")
    for slot in ("RESEARCH", "SUMMARIZATION", "COMPRESSION", "QA", "WRITER"):
        monkeypatch.setattr(settings, f"LLM_PROVIDER_{slot}", None)
        monkeypatch.setattr(settings, f"LLM_MODEL_{slot}", None)
    monkeypatch.setattr(settings, "LLM_TIER_SUMMARIZATION", "strong")
    monkeypatch.setattr(settings, "LLM_TIER_RESEARCH", "balanced")
    monkeypatch.setattr(settings, "LLM_TIER_COMPRESSION", "fast")
    monkeypatch.setattr(settings, "LLM_TIER_QA", "balanced")
    monkeypatch.setattr(settings, "LLM_TIER_WRITER", "strong")
    for provider in ("DOUBAO", "OPENAI", "QWEN"):
        for tier in ("STRONG", "BALANCED", "FAST"):
            monkeypatch.setattr(settings, f"{provider}_MODEL_{tier}", None)


def test_resolve_slot_uses_active_provider_tier_catalog(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "DOUBAO_MODEL_BALANCED", "ep-doubao-balanced")

    provider_name, model_name = resolve_slot(
        slot="research",
        providers={"doubao": _DummyProvider(default_model="ep-default")},
    )

    assert provider_name == "doubao"
    assert model_name == "ep-doubao-balanced"


def test_resolve_slot_uses_provider_default_when_catalog_slot_empty(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)

    provider_name, model_name = resolve_slot(
        slot="compression",
        providers={"doubao": _DummyProvider(default_model="ep-default")},
    )

    assert provider_name == "doubao"
    assert model_name == "ep-default"


def test_resolve_slot_respects_per_slot_provider_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "LLM_PROVIDER_QA", "qwen")
    monkeypatch.setattr(settings, "QWEN_MODEL_BALANCED", "qwen-plus-catalog")

    provider_name, model_name = resolve_slot(
        slot="qa",
        providers={
            "doubao": _DummyProvider(default_model="ep-default"),
            "qwen": _DummyProvider(default_model="qwen-plus-default"),
        },
    )

    assert provider_name == "qwen"
    assert model_name == "qwen-plus-catalog"


def test_resolve_slot_model_override_has_highest_priority(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "DOUBAO_MODEL_BALANCED", "ep-doubao-balanced")
    monkeypatch.setattr(settings, "LLM_MODEL_RESEARCH", "ep-direct-override")

    provider_name, model_name = resolve_slot(
        slot="research",
        providers={"doubao": _DummyProvider(default_model="ep-default")},
    )

    assert provider_name == "doubao"
    assert model_name == "ep-direct-override"


def test_resolve_slot_supports_cross_provider_tier_catalog(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "LLM_ACTIVE_PROVIDER", "qwen")
    monkeypatch.setattr(settings, "QWEN_MODEL_STRONG", "qwen-max-catalog")

    provider_name, model_name = resolve_slot(
        slot="writer",
        providers={"qwen": _DummyProvider(default_model="qwen-plus-default")},
    )

    assert provider_name == "qwen"
    assert model_name == "qwen-max-catalog"


def test_resolve_slot_rejects_unsupported_slot() -> None:
    with pytest.raises(LLMRequestError):
        resolve_slot(
            slot="invalid-slot",
            providers={"doubao": _DummyProvider(default_model="ep-default")},
        )


def test_resolve_slot_rejects_missing_provider(monkeypatch: pytest.MonkeyPatch) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "LLM_PROVIDER_QA", "openai")

    with pytest.raises(LLMRequestError):
        resolve_slot(
            slot="qa",
            providers={"doubao": _DummyProvider(default_model="ep-default")},
        )


def test_resolve_slot_rejects_invalid_tier(monkeypatch: pytest.MonkeyPatch) -> None:
    _reset_routing_settings(monkeypatch)
    monkeypatch.setattr(settings, "LLM_TIER_RESEARCH", "premium")

    with pytest.raises(LLMRequestError):
        resolve_slot(
            slot="research",
            providers={"doubao": _DummyProvider(default_model="ep-default")},
        )


def test_resolve_slot_rejects_empty_catalog_and_default(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_routing_settings(monkeypatch)

    with pytest.raises(LLMRequestError):
        resolve_slot(
            slot="research",
            providers={"doubao": _DummyProvider(default_model="")},
        )

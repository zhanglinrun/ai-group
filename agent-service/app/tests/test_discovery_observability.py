from __future__ import annotations

from types import SimpleNamespace

import pytest

from agents.nodes.discovery import (
    _build_snippet_sample,
    _filter_discovery_candidates,
    discovery_node,
)
from schemas.agent_outputs import DiscoveryExtractOutput
from schemas.intake import RunIntakeDraft
from service.collector.base import CollectorObservation, CollectorSnippet, ToolObservationResult
from service.llm.response import LLMResponse


class _FakeDiscoveryRegistry:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def invoke(self, action: str, *, args: dict[str, object]) -> CollectorObservation:
        self.calls.append((action, dict(args)))
        quote = "纷享销客是中国 CRM 和销售管理软件厂商。"
        return CollectorObservation(
            channel="search_web",
            args=args,
            result=ToolObservationResult(
                snippets=[
                    CollectorSnippet(
                        quote=quote,
                        sanitized_text=quote,
                        source_url="https://example.cn/crm",
                        source_title="CRM",
                        source_type="article",
                        desensitized=True,
                    )
                ],
                metadata={},
            ),
        )


class _FakeDiscoverySession:
    def __init__(self) -> None:
        self.step = SimpleNamespace(payload={}, status="running", finished_at=None)

    async def __aenter__(self) -> "_FakeDiscoverySession":
        return self

    async def __aexit__(self, *_: object) -> None:
        return None

    def add(self, item: object) -> None:
        if item.__class__.__name__ == "Step":
            self.step = item

    async def commit(self) -> None:
        return None

    async def get(self, model: object, key: str) -> object | None:
        del key
        if getattr(model, "__name__", "") == "Step":
            return self.step
        return None


def test_build_snippet_sample_uses_sanitized_preview() -> None:
    snippet = CollectorSnippet(
        quote="raw quote",
        sanitized_text="safe text " * 40,
        source_url="https://example.com/pricing",
        source_title="Example Pricing",
        source_type="pricing_page",
        desensitized=True,
    )

    sample = _build_snippet_sample(snippet=snippet, query="example pricing")

    assert sample is not None
    assert sample["source_title"] == "Example Pricing"
    assert sample["source_url"] == "https://example.com/pricing"
    assert sample["source_type"] == "pricing_page"
    assert sample["query"] == "example pricing"
    quote_preview = sample["quote_preview"]
    assert isinstance(quote_preview, str)
    assert quote_preview.startswith("safe text")
    assert len(quote_preview) == 220


def test_filter_discovery_candidates_keeps_grounded_competitor() -> None:
    quote = "Cursor is an AI code editor used by software teams."
    candidate = SimpleNamespace(
        name="Cursor",
        is_competitor=True,
        relevance_reason="AI coding product in the target market.",
        evidence_quote=quote,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[f"Article summary: {quote}"],
        snippet_rows=[],
    )

    assert discovered == ["Cursor"]
    assert filtered_out == []
    assert relevance == [
        {
            "name": "Cursor",
            "candidate_role": "direct_competitor",
            "relevance_reason": "AI coding product in the target market.",
            "evidence_quote_preview": quote,
        }
    ]


def test_filter_discovery_candidates_filters_non_competitor() -> None:
    candidate = SimpleNamespace(
        name="TechCrunch",
        is_competitor=False,
        relevance_reason="Publisher, not a direct competitor.",
        evidence_quote="TechCrunch reported on AI coding tools.",
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=["TechCrunch reported on AI coding tools."],
        snippet_rows=[],
    )

    assert discovered == []
    assert relevance == []
    assert filtered_out == [{"name": "TechCrunch", "reason": "not_competitor"}]


def test_filter_discovery_candidates_dedupes_alias_key() -> None:
    quote = "OpenAI Codex helps developers write code."
    candidates = [
        SimpleNamespace(
            name="OpenAI Codex",
            is_competitor=True,
            relevance_reason="AI coding product.",
            evidence_quote=quote,
        ),
        SimpleNamespace(
            name="OpenAI-Codex",
            is_competitor=True,
            relevance_reason="Duplicate alias.",
            evidence_quote=quote,
        ),
    ]

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=candidates,
        snippets=[quote],
        snippet_rows=[],
    )

    assert discovered == ["OpenAI Codex"]
    assert len(relevance) == 1
    assert filtered_out == [{"name": "OpenAI-Codex", "reason": "duplicate_alias"}]


def test_filter_discovery_candidates_filters_grounding_miss() -> None:
    candidate = SimpleNamespace(
        name="Windsurf",
        is_competitor=True,
        relevance_reason="AI coding product.",
        evidence_quote="Windsurf was named as a direct competitor.",
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=["The page only mentions Cursor."],
        snippet_rows=[],
    )

    assert discovered == []
    assert relevance == []
    assert filtered_out == [{"name": "Windsurf", "reason": "grounding_miss"}]


def test_filter_discovery_candidates_backfills_validated_official_source() -> None:
    quote = "Cursor is an AI code editor used by software teams."
    candidate = SimpleNamespace(
        name="Cursor",
        is_competitor=True,
        relevance_reason="AI coding product in the target market.",
        evidence_quote=quote,
        official_url=None,
        source_domain=None,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[f"Article summary: {quote}"],
        snippet_rows=[
            {
                "text": quote,
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
            }
        ],
    )

    assert discovered == ["Cursor"]
    assert filtered_out == []
    assert relevance == [
        {
            "name": "Cursor",
            "candidate_role": "direct_competitor",
            "relevance_reason": "AI coding product in the target market.",
            "evidence_quote_preview": quote,
            "official_url": "https://cursor.com/pricing",
            "source_domain": "cursor.com",
        }
    ]


def test_filter_discovery_candidates_filters_upstream_suppliers_from_core_queue() -> None:
    quote = "NVIDIA provides AI chips for smart glasses and wearable devices."
    candidate = SimpleNamespace(
        name="NVIDIA",
        is_competitor=True,
        candidate_role="upstream_supplier",
        relevance_reason="上游芯片供应商，不是 AI 眼镜核心竞争样本。",
        evidence_quote=quote,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[quote],
        snippet_rows=[],
        self_product="AI眼镜",
    )

    assert discovered == []
    assert relevance == []
    assert filtered_out == [
        {
            "name": "NVIDIA",
            "reason": "non_core_candidate_role",
            "candidate_role": "upstream_supplier",
        }
    ]


def test_filter_discovery_candidates_reconciles_llm_direct_role_to_upstream_signal() -> None:
    quote = "NVIDIA provides GPU chips for smart glasses and AI edge devices."
    candidate = SimpleNamespace(
        name="NVIDIA",
        is_competitor=True,
        candidate_role="direct_competitor",
        relevance_reason="GPU 芯片供应商，上游算力厂商。",
        evidence_quote=quote,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[quote],
        snippet_rows=[],
        self_product="AI眼镜",
        analysis_archetype="landscape",
    )

    assert discovered == ["NVIDIA"]
    assert filtered_out == []
    assert relevance[0]["candidate_role"] == "upstream_supplier"


def test_filter_discovery_candidates_still_filters_reconciled_upstream_in_comparison() -> None:
    quote = "NVIDIA provides GPU chips for smart glasses and AI edge devices."
    candidate = SimpleNamespace(
        name="NVIDIA",
        is_competitor=True,
        candidate_role="direct_competitor",
        relevance_reason="GPU 芯片供应商，上游算力厂商。",
        evidence_quote=quote,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[quote],
        snippet_rows=[],
        self_product="AI眼镜",
        analysis_archetype="comparison",
    )

    assert discovered == []
    assert relevance == []
    assert filtered_out == [
        {
            "name": "NVIDIA",
            "reason": "non_core_candidate_role",
            "candidate_role": "upstream_supplier",
        }
    ]


def test_filter_discovery_candidates_prioritizes_direct_candidates() -> None:
    direct_quote = "Meta Ray-Ban smart glasses are AI wearable products."
    adjacent_quote = "XREAL makes AR glasses for spatial computing."
    substitute_quote = "Smartphone assistants can substitute some AI glasses workflows."
    candidates = [
        SimpleNamespace(
            name="Phone Assistant",
            is_competitor=True,
            candidate_role="substitute",
            relevance_reason="替代部分 AI 眼镜工作流。",
            evidence_quote=substitute_quote,
        ),
        SimpleNamespace(
            name="Meta Ray-Ban",
            is_competitor=True,
            candidate_role="direct_competitor",
            relevance_reason="AI 眼镜核心竞争样本。",
            evidence_quote=direct_quote,
        ),
        SimpleNamespace(
            name="XREAL",
            is_competitor=True,
            candidate_role="adjacent_competitor",
            relevance_reason="相邻 AR 眼镜玩家。",
            evidence_quote=adjacent_quote,
        ),
    ]

    discovered, _, relevance = _filter_discovery_candidates(
        candidates=candidates,
        snippets=[direct_quote, adjacent_quote, substitute_quote],
        snippet_rows=[],
        self_product="AI眼镜",
    )

    assert discovered == ["Meta Ray-Ban", "XREAL", "Phone Assistant"]
    assert [item["candidate_role"] for item in relevance] == [
        "direct_competitor",
        "adjacent_competitor",
        "substitute",
    ]


def test_filter_discovery_candidates_does_not_pollute_role_with_global_trend_intent() -> None:
    quote = "Meta Ray-Ban smart glasses are AI wearable products."
    candidate = SimpleNamespace(
        name="Meta Ray-Ban",
        is_competitor=True,
        relevance_reason="AI 眼镜产品，覆盖第一视角拍摄和语音助手。",
        evidence_quote=quote,
    )

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=[candidate],
        snippets=[quote],
        snippet_rows=[],
        domain_context="AI硬件发展趋势",
        analysis_intent="分析发展趋势并寻找商业机会",
        self_product="AI眼镜",
        analysis_archetype="landscape",
    )

    assert discovered == ["Meta Ray-Ban"]
    assert filtered_out == []
    assert relevance[0]["candidate_role"] == "direct_competitor"


def test_filter_discovery_candidates_landscape_promotes_non_media_when_core_missing() -> None:
    product_quote = "Meta Ray-Ban smart glasses provide AI assistant and camera features."
    media_quote = "IDC released an industry trend report for AI hardware growth."
    upstream_quote = "NVIDIA provides GPU chips for AI smart glasses."
    candidates = [
        SimpleNamespace(
            name="Meta Ray-Ban",
            is_competitor=True,
            candidate_role="trend_reference",
            relevance_reason="智能眼镜产品，覆盖语音助手与拍摄能力。",
            evidence_quote=product_quote,
        ),
        SimpleNamespace(
            name="IDC",
            is_competitor=True,
            candidate_role="trend_reference",
            relevance_reason="行业研究机构趋势报告。",
            evidence_quote=media_quote,
        ),
        SimpleNamespace(
            name="NVIDIA",
            is_competitor=True,
            candidate_role="upstream_supplier",
            relevance_reason="上游芯片供应商。",
            evidence_quote=upstream_quote,
        ),
    ]

    discovered, filtered_out, relevance = _filter_discovery_candidates(
        candidates=candidates,
        snippets=[product_quote, media_quote, upstream_quote],
        snippet_rows=[],
        self_product="AI眼镜",
        analysis_archetype="landscape",
    )

    assert filtered_out == []
    assert discovered[0] == "Meta Ray-Ban"
    assert relevance[0]["candidate_role"] == "adjacent_competitor"
    assert any(item["candidate_role"] in {"direct_competitor", "adjacent_competitor", "substitute"} for item in relevance)


@pytest.mark.asyncio
async def test_discovery_node_passes_locale_to_search_router(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = _FakeDiscoveryRegistry()
    fake_session = _FakeDiscoverySession()

    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        output = DiscoveryExtractOutput.parse_llm_content(
            {
                "candidates": [
                    {
                        "name": "纷享销客",
                        "is_competitor": True,
                        "relevance_reason": "中国市场 CRM 竞品。",
                        "evidence_quote": "纷享销客是中国 CRM 和销售管理软件厂商。",
                    }
                ]
            }
        )
        return SimpleNamespace(
            value=output,
            outcome="primary",
            llm_response=LLMResponse(
                model_slot="research",
                provider="fake",
                model_name="fake",
                prompt_preview="fake",
                prompt_hash="fake",
                content={},
                prompt_tokens=1,
                completion_tokens=1,
                latency_ms=1,
                error=None,
            ),
        )

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    monkeypatch.setattr("agents.nodes.discovery.get_channel_registry", lambda: registry)
    monkeypatch.setattr("agents.nodes.discovery.get_session_factory", lambda: lambda: fake_session)
    monkeypatch.setattr("agents.nodes.discovery.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.discovery.emit_run_event", _fake_emit_run_event)

    result = await discovery_node(
        {
            "run_id": "run_discovery_locale",
            "user_query": "国内 CRM 销售 AI 工具",
            "pending_tool_args": {
                "search_queries": ["国内 CRM 销售 AI 工具 竞品"],
                "domain_context": "CRM",
            },
            "intake_draft": RunIntakeDraft(
                user_query="国内 CRM 销售 AI 工具",
                user_role="sales",
                analysis_intent="寻找中国市场销售团队可用的 AI CRM 工具",
                competitors_discovery_mode=True,
                market_scope="中国市场",
                response_language="zh",
            ),
        }
    )

    assert result["discovered_competitors"] == ["纷享销客"]
    assert registry.calls == [
        (
            "search_web",
                {
                    "query": "国内 CRM 销售 AI 工具 竞品",
                    "max_results": 8,
                    "response_language": "zh",
                    "market_scope": "中国市场",
                },
        )
    ]

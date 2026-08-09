from __future__ import annotations

import re
import time
from collections.abc import Sequence
from datetime import datetime, timezone
from urllib.parse import urlsplit

from agents.nodes.planner import reconcile_plan_tree_after_discovery
from agents.state import AgentState
from agents.state_coercion import coerce_plan_tree
from agents.tools import get_channel_registry
from core.defaults import (
    DEFAULT_DISCOVER_MAX_RESULTS,
    DISCOVERY_SEARCH_MAX_RESULTS_CAP,
    DISCOVERY_SNIPPETS_TO_EXTRACT,
    MAX_DISCOVERY_SEARCH_QUERIES,
)
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from models.run import Run
from models.step import Step
from schemas.ids import make_id
from schemas.intake import category_aliases_for_target, normalize_optional_text, text_mentions_any_term
from service.collector.errors import ChannelError
from service.event_bus import RunEventType, emit_run_event
from schemas.agent_outputs import DiscoveryExtractOutput
from service.llm import (
    DISCOVERY_EXTRACT_SYSTEM_PROMPT,
    build_discovery_extract_fallback_user_prompt,
    build_discovery_extract_repair_user_prompt,
    build_discovery_extract_user_prompt,
)
from service.llm.harness import complete_structured
from utils.log_node import log_node
from utils.logger import bind_step, get_logger

log = get_logger("agents.discovery")
_DISCOVERY_SNIPPET_SAMPLE_LIMIT = 3
_DISCOVERY_SNIPPET_PREVIEW_LIMIT = 220
_DISCOVERY_EVIDENCE_PREVIEW_LIMIT = 220
_DISCOVERY_OFFICIAL_PATH_KEYWORDS: tuple[str, ...] = (
    "/pricing",
    "/docs",
    "/enterprise",
    "/changelog",
    "/product",
    "/about",
)
_DISCOVERY_NON_OFFICIAL_HOST_HINTS: tuple[str, ...] = (
    "wikipedia.org",
    "reddit.com",
    "medium.com",
    "g2.com",
    "capterra.com",
    "youtube.com",
    "techcrunch.com",
    "news.ycombinator.com",
    # Market-research / analyst / aggregator domains: valid as evidence, but never
    # a competitor's OWN site, so they must not be accepted as official_url.
    "marketsandmarkets.com",
    "statista.com",
    "grandviewresearch.com",
    "mordorintelligence.com",
    "gartner.com",
    "idc.com",
)
_DISCOVERY_GENERIC_NAME_TOKENS: frozenset[str] = frozenset(
    {"ai", "app", "tool", "tools", "software", "assistant", "the", "inc", "labs", "lab"}
)
_DISCOVERY_CORE_ROLES: frozenset[str] = frozenset(
    {"direct_competitor", "adjacent_competitor", "substitute"}
)
_DISCOVERY_ROLE_PRIORITY: dict[str, int] = {
    "direct_competitor": 0,
    "adjacent_competitor": 1,
    "substitute": 2,
    "upstream_supplier": 3,
    "trend_reference": 4,
}
_DISCOVERY_UPSTREAM_HINTS: tuple[str, ...] = (
    "upstream",
    "supplier",
    "chip",
    "chipset",
    "semiconductor",
    "gpu",
    "cpu",
    "processor",
    "算力",
    "芯片",
    "半导体",
    "供应商",
    "上游",
)
_DISCOVERY_TREND_HINTS: tuple[str, ...] = (
    "trend",
    "reference",
    "report",
    "media",
    "趋势",
    "报告",
    "媒体",
    "研究机构",
)
_DISCOVERY_SUBSTITUTE_HINTS: tuple[str, ...] = (
    "substitute",
    "alternative",
    "替代",
    "替代品",
)
_DISCOVERY_ADJACENT_HINTS: tuple[str, ...] = (
    "adjacent",
    "platform",
    "ecosystem",
    "相邻",
    "生态",
    "平台",
)
_DISCOVERY_SEGMENT_HINTS: tuple[str, ...] = (
    "眼镜",
    "glasses",
    "ar",
    "wearable",
    "可穿戴",
)


def _clean_optional_string(value: object) -> str | None:
    return normalize_optional_text(value)


def _state_or_intake_string(state: AgentState, field_name: str) -> str | None:
    direct = _clean_optional_string(state.get(field_name))
    if direct is not None:
        return direct
    intake_draft = state.get("intake_draft")
    if intake_draft is None:
        return None
    if isinstance(intake_draft, dict):
        return _clean_optional_string(intake_draft.get(field_name))
    return _clean_optional_string(getattr(intake_draft, field_name, None))


def _state_or_intake_string_list(state: AgentState, field_name: str) -> list[str]:
    raw = state.get(field_name)
    if isinstance(raw, list):
        return [item.strip() for item in raw if isinstance(item, str) and item.strip()]
    intake_draft = state.get("intake_draft")
    if isinstance(intake_draft, dict):
        raw = intake_draft.get(field_name)
    elif intake_draft is not None:
        raw = getattr(intake_draft, field_name, None)
    else:
        raw = None
    if not isinstance(raw, list):
        return []
    return [item.strip() for item in raw if isinstance(item, str) and item.strip()]


def _state_response_language(state: AgentState) -> str | None:
    value = _state_or_intake_string(state, "response_language")
    return value if value in {"zh", "en"} else None


def _state_analysis_archetype(state: AgentState) -> str:
    raw = _clean_optional_string(state.get("analysis_archetype"))
    if raw in {"comparison", "landscape"}:
        return raw
    intake_draft = state.get("intake_draft")
    if isinstance(intake_draft, dict):
        intake_raw = _clean_optional_string(intake_draft.get("analysis_archetype"))
        if intake_raw in {"comparison", "landscape"}:
            return intake_raw
    elif intake_draft is not None:
        intake_raw = _clean_optional_string(getattr(intake_draft, "analysis_archetype", None))
        if intake_raw in {"comparison", "landscape"}:
            return intake_raw
    return "comparison"


def _normalize_alias_key(value: str) -> str:
    lowered = value.casefold()
    without_punctuation = re.sub(r"[^\w\s]", " ", lowered, flags=re.UNICODE)
    return " ".join(without_punctuation.split())


def _normalize_grounding_text(value: str) -> str:
    return " ".join(value.casefold().split())


def _quote_is_grounded(*, evidence_quote: str, snippets: Sequence[str]) -> bool:
    normalized_quote = _normalize_grounding_text(evidence_quote)
    if not normalized_quote:
        return False
    return any(normalized_quote in _normalize_grounding_text(snippet) for snippet in snippets)


def _source_domain(source_url: object) -> str | None:
    if not isinstance(source_url, str):
        return None
    stripped = source_url.strip()
    if not stripped:
        return None
    parsed = urlsplit(stripped)
    host = parsed.netloc.lower().removeprefix("www.")
    return host or None


def _is_plausible_official_url(url: str | None) -> bool:
    """Cheap structural sanity for an LLM-proposed official URL.

    Discovery cannot fully verify an official site without fetching it; the
    researcher does that downstream (fetch_url + category floor + quality gates).
    We only reject the obviously-unusable here — non-http(s), bare/IP hosts, and
    aggregator/news hosts that are never a vendor's own site.
    """
    if not isinstance(url, str) or not url.strip():
        return False
    parsed = urlsplit(url.strip())
    if parsed.scheme not in {"http", "https"}:
        return False
    host = parsed.netloc.lower().removeprefix("www.")
    if not host or "." not in host or host.replace(".", "").isdigit():
        return False
    return not any(host == blocked or host.endswith(f".{blocked}") for blocked in _DISCOVERY_NON_OFFICIAL_HOST_HINTS)


def _candidate_name_tokens(name: str) -> set[str]:
    tokens = set(re.findall(r"[a-z0-9\u4e00-\u9fff]+", name.casefold()))
    return {
        token
        for token in tokens
        if token not in _DISCOVERY_GENERIC_NAME_TOKENS and (len(token) >= 2 or not token.isascii())
    }


def _text_mentions_candidate(*, candidate_name: str, text: str | None) -> bool:
    if not text:
        return False
    normalized_text = text.casefold()
    if candidate_name.casefold() in normalized_text:
        return True
    name_tokens = _candidate_name_tokens(candidate_name)
    if not name_tokens:
        return False
    return any(token in normalized_text for token in name_tokens)


def _normalize_candidate_role(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip().casefold()
    if normalized in _DISCOVERY_ROLE_PRIORITY:
        return normalized
    return None


def _infer_candidate_role(
    *,
    candidate_name: str,
    relevance_reason: str,
    self_product: str | None,
) -> str:
    combined = " ".join(
        item
        for item in [candidate_name, relevance_reason]
        if isinstance(item, str) and item.strip()
    ).casefold()
    if any(hint in combined for hint in _DISCOVERY_UPSTREAM_HINTS):
        return "upstream_supplier"
    if any(hint in combined for hint in _DISCOVERY_TREND_HINTS):
        return "trend_reference"
    if any(hint in combined for hint in _DISCOVERY_SUBSTITUTE_HINTS):
        return "substitute"
    if any(hint in combined for hint in _DISCOVERY_ADJACENT_HINTS):
        return "adjacent_competitor"
    if isinstance(self_product, str) and self_product.strip():
        self_tokens = _candidate_name_tokens(self_product)
        if self_tokens and any(token in combined for token in self_tokens):
            return "direct_competitor"
    return "direct_competitor"


def _reconcile_candidate_role(
    *,
    llm_candidate_role: str | None,
    candidate_name: str,
    relevance_reason: str,
    domain_context: str | None = None,
    analysis_intent: str | None = None,
    self_product: str | None,
) -> str:
    context_text = " ".join(
        item for item in [domain_context, analysis_intent] if isinstance(item, str) and item.strip()
    ).casefold()
    landscape_signal = any(
        hint in context_text
        for hint in (
            "trend",
            "landscape",
            "opportunity",
            "全景",
            "趋势",
            "机会",
            "赛道",
        )
    )
    inferred_role = _infer_candidate_role(
        candidate_name=candidate_name,
        relevance_reason=relevance_reason,
        self_product=self_product,
    )
    if llm_candidate_role is None:
        return inferred_role
    if (
        llm_candidate_role in {"direct_competitor", "adjacent_competitor", "substitute"}
        and inferred_role in {"upstream_supplier", "trend_reference"}
    ):
        # Guardrail: heterogeneous entities should not be promoted into comparable
        # competitor roles by extraction noise.
        return inferred_role
    if llm_candidate_role == "direct_competitor" and inferred_role == "substitute":
        return "substitute"
    if (
        llm_candidate_role == "direct_competitor"
        and inferred_role == "adjacent_competitor"
        and landscape_signal
    ):
        return "adjacent_competitor"
    return llm_candidate_role


def _infer_admission_status(
    *,
    candidate_role: str,
    relevance_reason: str,
    evidence_quote: str,
    target_category: str | None,
    category_aliases: list[str],
    excluded_categories: list[str],
    market_segments: list[str],
    scope_policy: str | None,
) -> tuple[str, str]:
    combined = f"{relevance_reason} {evidence_quote}"
    if excluded_categories and text_mentions_any_term(combined, excluded_categories):
        return "excluded", "matched_excluded_category"
    aliases = category_aliases or category_aliases_for_target(target_category)
    target_hit = bool(aliases and text_mentions_any_term(combined, aliases))
    segment_terms = [*market_segments]
    if scope_policy == "broad_market":
        segment_terms.extend(_DISCOVERY_SEGMENT_HINTS)
    segment_hit = bool(segment_terms and text_mentions_any_term(combined, segment_terms))
    if candidate_role == "upstream_supplier":
        return "value_chain", "upstream_or_supplier_role"
    if candidate_role == "trend_reference":
        return "watchlist", "trend_or_media_reference_role"
    if candidate_role in _DISCOVERY_CORE_ROLES and target_hit:
        return "main_player", "matched_target_category"
    if candidate_role in _DISCOVERY_CORE_ROLES and segment_hit:
        return "segment_player", "matched_adjacent_market_segment"
    if candidate_role in _DISCOVERY_CORE_ROLES and target_category is None:
        return "main_player", "no_target_category_available"
    return "watchlist", "missing_target_category_evidence"


def _score_official_source_candidate(
    *,
    candidate_name: str,
    source_url: str | None,
    source_title: str | None,
    snippet_text: str,
) -> int:
    if source_url is None:
        return -99
    parsed = urlsplit(source_url)
    host = parsed.netloc.lower().removeprefix("www.")
    if not host:
        return -99
    if any(host == blocked or host.endswith(f".{blocked}") for blocked in _DISCOVERY_NON_OFFICIAL_HOST_HINTS):
        return -99
    score = 0
    if _text_mentions_candidate(candidate_name=candidate_name, text=source_title):
        score += 2
    if _text_mentions_candidate(candidate_name=candidate_name, text=snippet_text):
        score += 1
    if any(keyword in parsed.path.lower() for keyword in _DISCOVERY_OFFICIAL_PATH_KEYWORDS):
        score += 1
    host_tokens = set(re.findall(r"[a-z0-9]+", host))
    if host_tokens & _candidate_name_tokens(candidate_name):
        score += 1
    return score


def _resolve_validated_official_source(
    *,
    candidate_name: str,
    evidence_quote: str,
    llm_official_url: str | None,
    llm_source_domain: str | None,
    snippet_rows: Sequence[dict[str, object]],
) -> tuple[str | None, str | None, str | None]:
    normalized_quote = _normalize_grounding_text(evidence_quote)
    best_url: str | None = None
    best_domain: str | None = None
    best_score = -99

    for row in snippet_rows:
        snippet_text_raw = row.get("text")
        if not isinstance(snippet_text_raw, str) or not snippet_text_raw.strip():
            continue
        snippet_text = snippet_text_raw.strip()
        if normalized_quote and normalized_quote not in _normalize_grounding_text(snippet_text):
            continue
        source_url_raw = row.get("source_url")
        source_url = source_url_raw.strip() if isinstance(source_url_raw, str) and source_url_raw.strip() else None
        if source_url is None:
            continue
        source_title_raw = row.get("source_title")
        source_title = (
            source_title_raw.strip()
            if isinstance(source_title_raw, str) and source_title_raw.strip()
            else None
        )
        score = _score_official_source_candidate(
            candidate_name=candidate_name,
            source_url=source_url,
            source_title=source_title,
            snippet_text=snippet_text,
        )
        if score > best_score:
            best_score = score
            best_url = source_url
            best_domain = _source_domain(source_url)

    if best_score >= 2:
        return best_url, best_domain, "corroborated"

    llm_url = llm_official_url.strip() if isinstance(llm_official_url, str) and llm_official_url.strip() else None
    if llm_url is None:
        return None, None, None
    llm_domain = _source_domain(llm_url)
    if llm_domain is None:
        return None, None, None
    for row in snippet_rows:
        source_url_raw = row.get("source_url")
        row_url = source_url_raw.strip() if isinstance(source_url_raw, str) and source_url_raw.strip() else None
        if row_url is None or _source_domain(row_url) != llm_domain:
            continue
        row_title_raw = row.get("source_title")
        row_title = row_title_raw if isinstance(row_title_raw, str) else None
        row_text_raw = row.get("text")
        row_text = row_text_raw if isinstance(row_text_raw, str) else ""
        if _text_mentions_candidate(candidate_name=candidate_name, text=row_title) or _text_mentions_candidate(
            candidate_name=candidate_name,
            text=row_text,
        ):
            return llm_url, llm_source_domain or llm_domain, "corroborated"

    # No snippet corroboration, but the LLM lead is structurally usable: hand it
    # to the researcher as a provisional source-first target instead of dropping
    # it (which starved source-first fetch and forced shallow web search).
    if _is_plausible_official_url(llm_url):
        return llm_url, llm_source_domain or llm_domain, "llm_provisional"
    return None, None, None


def _filter_discovery_candidates(
    *,
    candidates: Sequence[object],
    snippets: Sequence[str],
    snippet_rows: Sequence[dict[str, object]],
    domain_context: str | None = None,
    analysis_intent: str | None = None,
    self_product: str | None = None,
    analysis_archetype: str = "comparison",
    target_category: str | None = None,
    category_aliases: list[str] | None = None,
    excluded_categories: list[str] | None = None,
    market_segments: list[str] | None = None,
    scope_policy: str | None = None,
) -> tuple[list[str], list[dict[str, object]], list[dict[str, object]]]:
    discovered: list[str] = []
    filtered_out: list[dict[str, object]] = []
    relevance: list[dict[str, object]] = []
    seen_aliases: set[str] = set()

    for candidate in candidates:
        name = str(getattr(candidate, "name", "") or "").strip()
        is_competitor = bool(getattr(candidate, "is_competitor", False))
        relevance_reason = str(getattr(candidate, "relevance_reason", "") or "").strip()
        evidence_quote = str(getattr(candidate, "evidence_quote", "") or "").strip()
        llm_candidate_role = _normalize_candidate_role(getattr(candidate, "candidate_role", None))
        candidate_role = _reconcile_candidate_role(
            llm_candidate_role=llm_candidate_role,
            candidate_name=name,
            relevance_reason=relevance_reason,
            domain_context=domain_context,
            analysis_intent=analysis_intent,
            self_product=self_product,
        )
        llm_official_url = getattr(candidate, "official_url", None)
        llm_source_domain = getattr(candidate, "source_domain", None)
        segment = _clean_optional_string(getattr(candidate, "segment", None))
        introduction = _clean_optional_string(getattr(candidate, "introduction", None))
        vendor = _clean_optional_string(getattr(candidate, "vendor", None))
        alias_key = _normalize_alias_key(name)
        admission_status, admission_reason = _infer_admission_status(
            candidate_role=candidate_role,
            relevance_reason=relevance_reason,
            evidence_quote=evidence_quote,
            target_category=target_category,
            category_aliases=list(category_aliases or []),
            excluded_categories=list(excluded_categories or []),
            market_segments=list(market_segments or []),
            scope_policy=scope_policy,
        )

        if not name:
            filtered_out.append({"name": "", "reason": "blank_name"})
            continue
        if not is_competitor:
            filtered_out.append({"name": name, "reason": "not_competitor"})
            continue
        if admission_status == "excluded":
            filtered_out.append(
                {
                    "name": name,
                    "reason": admission_reason,
                    "candidate_role": candidate_role,
                    "admission_status": admission_status,
                }
            )
            continue
        if candidate_role not in _DISCOVERY_CORE_ROLES and analysis_archetype != "landscape":
            filtered_out.append(
                {
                    "name": name,
                    "reason": "non_core_candidate_role",
                    "candidate_role": candidate_role,
                }
            )
            continue
        if not evidence_quote:
            filtered_out.append({"name": name, "reason": "missing_evidence_quote"})
            continue
        if not _quote_is_grounded(evidence_quote=evidence_quote, snippets=snippets):
            filtered_out.append({"name": name, "reason": "grounding_miss"})
            continue
        if not alias_key:
            filtered_out.append({"name": name, "reason": "blank_alias_key"})
            continue
        if alias_key in seen_aliases:
            filtered_out.append({"name": name, "reason": "duplicate_alias"})
            continue

        seen_aliases.add(alias_key)
        discovered.append(name)
        official_url, source_domain, official_url_confidence = _resolve_validated_official_source(
            candidate_name=name,
            evidence_quote=evidence_quote,
            llm_official_url=llm_official_url if isinstance(llm_official_url, str) else None,
            llm_source_domain=llm_source_domain if isinstance(llm_source_domain, str) else None,
            snippet_rows=snippet_rows,
        )
        row: dict[str, object] = {
            "name": name,
            "candidate_role": candidate_role,
            "admission_status": admission_status,
            "admission_reason": admission_reason,
            "relevance_reason": relevance_reason,
            "evidence_quote_preview": evidence_quote[:_DISCOVERY_EVIDENCE_PREVIEW_LIMIT],
        }
        if segment is not None:
            row["segment"] = segment
        if introduction is not None:
            row["introduction"] = introduction
        if vendor is not None:
            row["vendor"] = vendor
        if official_url is not None:
            row["official_url"] = official_url
            row["source_domain"] = source_domain or _source_domain(official_url)
            row["official_url_confidence"] = official_url_confidence
        relevance.append(row)

    relevance.sort(
        key=lambda item: _DISCOVERY_ROLE_PRIORITY.get(
            str(item.get("candidate_role") or ""),
            99,
        )
    )
    discovered = [str(item["name"]) for item in relevance if isinstance(item.get("name"), str)]
    return discovered, filtered_out, relevance


def _build_snippet_sample(*, snippet: object, query: str) -> dict[str, object] | None:
    quote = getattr(snippet, "sanitized_text", None) or getattr(snippet, "quote", None)
    if not isinstance(quote, str) or not quote.strip():
        return None
    source_title = getattr(snippet, "source_title", None)
    source_url = getattr(snippet, "source_url", None)
    source_type = getattr(snippet, "source_type", None)
    return {
        "source_title": source_title if isinstance(source_title, str) else None,
        "source_url": source_url if isinstance(source_url, str) else None,
        "source_type": source_type if isinstance(source_type, str) else None,
        "quote_preview": quote.strip()[:_DISCOVERY_SNIPPET_PREVIEW_LIMIT],
        "query": query,
    }


@log_node("discovery")
async def discovery_node(state: AgentState) -> AgentState:
    """Execute competitor discovery via web search + LLM extraction."""
    run_id = state.get("run_id", "unknown")
    pending_tool_args = state.get("pending_tool_args", {})
    user_query = state.get("user_query", "")
    market_scope = _state_or_intake_string(state, "market_scope")
    response_language = _state_response_language(state)
    analysis_intent = _state_or_intake_string(state, "analysis_intent")
    self_product = _state_or_intake_string(state, "self_product")
    analysis_archetype = _state_analysis_archetype(state)
    target_category = _state_or_intake_string(state, "target_category")
    category_aliases = _state_or_intake_string_list(state, "category_aliases")
    excluded_categories = _state_or_intake_string_list(state, "excluded_categories")
    market_segments = _state_or_intake_string_list(state, "market_segments")
    scope_policy = _state_or_intake_string(state, "scope_policy")

    search_queries: list[str] = pending_tool_args.get("search_queries", [user_query])
    domain_context: str = pending_tool_args.get("domain_context", user_query)
    max_results: int = pending_tool_args.get("max_results", DEFAULT_DISCOVER_MAX_RESULTS)

    session_factory = get_session_factory()

    step_id = make_id("step_")
    async with session_factory() as session:
        step = Step(
            step_id=step_id,
            run_id=run_id,
            agent_name="discovery",
            status="running",
            retry_count=0,
            payload={"search_queries": search_queries, "domain_context": domain_context},
        )
        session.add(step)
        await session.commit()

    registry = get_channel_registry()
    all_snippets: list[str] = []
    all_snippet_rows: list[dict[str, object]] = []
    snippet_samples: list[dict[str, object]] = []

    for query in search_queries[:MAX_DISCOVERY_SEARCH_QUERIES]:
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.TOOL_START,
            step_id=step_id,
            payload={
                "tool": "search_web",
                "competitor_id": None,
                "dimension": None,
                "args_summary": {
                    "query": query,
                    "max_results": min(max_results, DISCOVERY_SEARCH_MAX_RESULTS_CAP),
                    **({"response_language": response_language} if response_language is not None else {}),
                    **({"market_scope": market_scope} if market_scope is not None else {}),
                },
            },
        )
        tool_started_at = time.monotonic()
        snippets_added = 0
        error_text: str | None = None
        try:
            search_args: dict[str, object] = {
                "query": query,
                "max_results": min(max_results, DISCOVERY_SEARCH_MAX_RESULTS_CAP),
            }
            if response_language is not None:
                search_args["response_language"] = response_language
            if market_scope is not None:
                search_args["market_scope"] = market_scope
            observation = await registry.invoke("search_web", args=search_args)
            for snippet in observation.result.snippets:
                text = snippet.sanitized_text or snippet.quote
                if text:
                    all_snippets.append(text[:500])
                    all_snippet_rows.append(
                        {
                            "text": text[:500],
                            "source_url": getattr(snippet, "source_url", None),
                            "source_title": getattr(snippet, "source_title", None),
                        }
                    )
                    snippets_added += 1
                    if len(snippet_samples) < _DISCOVERY_SNIPPET_SAMPLE_LIMIT:
                        sample = _build_snippet_sample(snippet=snippet, query=query)
                        if sample is not None:
                            snippet_samples.append(sample)
        except ChannelError as exc:
            # Channel boundary contract: every recoverable failure inside the
            # search channel (rate-limit, timeout, auth, no-snippet) is
            # surfaced as ChannelError. Anything else (asyncio.CancelledError,
            # KeyError from a bug, etc.) must propagate so node.error fires.
            error_text = f"{type(exc).__name__}: {exc}"
            with bind_step(step_id):
                log.warning(
                    "discovery.search_failed",
                    query=query,
                    error_type=type(exc).__name__,
                    error=str(exc)[:300],
                )
        latency_ms = int((time.monotonic() - tool_started_at) * 1000)
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.TOOL_FINISH,
            step_id=step_id,
            payload={
                "tool": "search_web",
                "competitor_id": None,
                "dimension": None,
                "success": error_text is None,
                "snippet_count": snippets_added,
                "latency_ms": latency_ms,
                "error": error_text[:300] if error_text else None,
            },
        )

    discovered: list[str] = []
    filtered_out_competitors: list[dict[str, object]] = []
    relevance: list[dict[str, object]] = []
    extract_error: str | None = None
    extract_outcome: str | None = None
    snippet_count = len(all_snippets)
    if all_snippets:
        combined_results = "\n---\n".join(all_snippets[:DISCOVERY_SNIPPETS_TO_EXTRACT])
        extract_prompt = build_discovery_extract_user_prompt(
            search_results=combined_results,
            domain_context=domain_context,
            user_query=user_query,
            market_scope=market_scope,
            analysis_intent=analysis_intent,
            self_product=self_product,
            target_category=target_category,
            category_aliases=category_aliases,
            excluded_categories=excluded_categories,
            market_segments=market_segments,
            scope_policy=scope_policy,
            response_language=response_language,
        )
        fallback_prompt = build_discovery_extract_fallback_user_prompt(
            domain_context=domain_context,
            user_query=user_query,
        )
        try:
            harness_result = await complete_structured(
                model_slot="research",
                system_prompt=DISCOVERY_EXTRACT_SYSTEM_PROMPT,
                user_prompt=extract_prompt,
                output_model=DiscoveryExtractOutput,
                parser=DiscoveryExtractOutput.parse_llm_content,
                fallback_system_prompt=DISCOVERY_EXTRACT_SYSTEM_PROMPT,
                fallback_user_prompt=fallback_prompt,
                repair_user_prompt_builder=lambda errors: build_discovery_extract_repair_user_prompt(
                    validation_errors=errors,
                    domain_context=domain_context,
                ),
                log_event="discovery.harness.finish",
            )
            extract_outcome = harness_result.outcome
            if harness_result.value is not None:
                discovered, filtered_out_competitors, relevance = _filter_discovery_candidates(
                    candidates=harness_result.value.candidates,
                    snippets=all_snippets,
                    snippet_rows=all_snippet_rows,
                    domain_context=domain_context,
                    analysis_intent=analysis_intent,
                    self_product=self_product,
                    analysis_archetype=analysis_archetype,
                    target_category=target_category,
                    category_aliases=category_aliases,
                    excluded_categories=excluded_categories,
                    market_segments=market_segments,
                    scope_policy=scope_policy,
                )
            elif harness_result.llm_response.error is not None:
                extract_error = harness_result.llm_response.error[:300]
            elif harness_result.schema_error is not None:
                # value is None without a provider error means the extract output
                # failed schema validation after retries; surface the reason so a
                # zero-competitor run is debuggable instead of "failed, reason null".
                extract_error = f"schema_invalid: {harness_result.schema_error}"[:300]
        except (KeyError, ValueError) as exc:
            extract_error = f"{type(exc).__name__}: {str(exc)[:300]}"
            with bind_step(step_id):
                log.exception(
                    "discovery.extract_failed",
                    error_type=type(exc).__name__,
                    snippet_count=snippet_count,
                )

    with bind_step(step_id):
        if snippet_count > 0 and not discovered:
            log.warning(
                "discovery.zero_competitors_with_snippets",
                snippet_count=snippet_count,
                extract_outcome=extract_outcome,
                extract_error=extract_error,
                filtered_out_count=len(filtered_out_competitors),
            )
        official_url_confidence_counts: dict[str, int] = {}
        for item in relevance:
            if not isinstance(item, dict) or item.get("official_url") is None:
                continue
            label = str(item.get("official_url_confidence") or "unknown")
            official_url_confidence_counts[label] = official_url_confidence_counts.get(label, 0) + 1
        log.info(
            "discovery.complete",
            discovered_count=len(discovered),
            discovered_competitors=discovered,
            snippet_count=snippet_count,
            snippet_samples=snippet_samples,
            filtered_out_competitors=filtered_out_competitors,
            relevance=relevance,
            queries=search_queries,
            extract_outcome=extract_outcome,
            extract_error=extract_error,
            official_url_with_url_count=sum(official_url_confidence_counts.values()),
            official_url_confidence_counts=official_url_confidence_counts,
        )

    async with session_factory() as session:
        step_record = await session.get(Step, step_id)
        if step_record is not None:
            step_record.status = "completed" if discovered else "failed"
            step_record.finished_at = datetime.now(timezone.utc)
            step_record.payload = {
                **(step_record.payload or {}),
                "discovered_competitors": discovered,
                "discovered_competitor_sources": {
                    str(item["name"]): {
                        "official_url": item.get("official_url"),
                        "source_domain": item.get("source_domain"),
                        "official_url_confidence": item.get("official_url_confidence"),
                        "candidate_role": item.get("candidate_role"),
                        "admission_status": item.get("admission_status"),
                        "admission_reason": item.get("admission_reason"),
                        "relevance_reason": item.get("relevance_reason"),
                        "segment": item.get("segment"),
                        "introduction": item.get("introduction"),
                        "vendor": item.get("vendor"),
                    }
                    for item in relevance
                    if isinstance(item, dict)
                    and isinstance(item.get("name"), str)
                },
                "snippet_count": snippet_count,
                "snippet_samples": snippet_samples,
                "filtered_out_competitors": filtered_out_competitors,
                "relevance": relevance,
                "extract_outcome": extract_outcome,
                "extract_error": extract_error,
            }
            await session.commit()

    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_FINISH,
        step_id=step_id,
        payload={"agent_name": "discovery", "discovered_competitors": discovered},
    )

    reconciled_plan_tree: dict[str, object] | None = None
    discovered_competitor_sources = {
        str(item["name"]): {
            "official_url": item.get("official_url"),
            "source_domain": item.get("source_domain"),
            "official_url_confidence": item.get("official_url_confidence"),
            "candidate_role": item.get("candidate_role"),
            "relevance_reason": item.get("relevance_reason"),
            "segment": item.get("segment"),
            "introduction": item.get("introduction"),
            "vendor": item.get("vendor"),
        }
        for item in relevance
        if isinstance(item, dict)
        and isinstance(item.get("name"), str)
    }
    plan = coerce_plan_tree(state.get("plan_tree"))
    if discovered and plan is not None:
        intake_draft = state.get("intake_draft")
        tier_profile = resolve_tier_profile(_state_or_intake_string(state, "report_depth"))
        focus_dimensions: list[str] | None = None
        if intake_draft is not None and hasattr(intake_draft, "focus_dimensions"):
            focus_dimensions = list(intake_draft.focus_dimensions)
        reconciled = reconcile_plan_tree_after_discovery(
            plan_tree=plan,
            discovered_competitors=discovered,
            existing_competitors=[
                item for item in state.get("competitors", []) if isinstance(item, str)
            ],
            discovered_competitor_sources=discovered_competitor_sources,
            focus_dimensions=focus_dimensions,
            analysis_archetype=analysis_archetype,
            scope_policy=scope_policy,
            max_competitors=tier_profile.max_competitors,
            max_dimensions=tier_profile.max_dimensions,
            landscape_core_deepdive_n=tier_profile.landscape_core_deepdive_n,
        )
        reconciled_plan_tree = reconciled.model_dump()
        async with session_factory() as session:
            run_row = await session.get(Run, run_id)
            if run_row is not None:
                run_row.plan_tree = reconciled_plan_tree
                await session.commit()
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.PLAN_RECONCILED,
            payload={
                "plan_id": reconciled.plan_id,
                "task_count": len(reconciled.tasks),
                "version": reconciled.version,
                "plan_tree": reconciled_plan_tree,
                "discovered_competitors": discovered,
                "discovered_competitor_sources": discovered_competitor_sources,
            },
        )

    # `competitors` is an operator.add accumulator. Discovery in an explicit-name
    # run re-finds names already on the list (Cursor/Copilot/...), so appending the
    # raw discovered set duplicates them in runs.competitors. Only append names that
    # are not already present (alias-normalized).
    existing_competitor_keys = {
        _normalize_alias_key(item)
        for item in state.get("competitors", [])
        if isinstance(item, str) and item.strip()
    }
    new_competitors = [
        name for name in discovered if _normalize_alias_key(name) not in existing_competitor_keys
    ]

    result: dict[str, object] = {
        "competitors": new_competitors,
        "discovered_competitors": discovered,
        "discovered_competitor_sources": discovered_competitor_sources,
        "last_completed_node": None,
    }
    if reconciled_plan_tree is not None:
        result["plan_tree"] = reconciled_plan_tree
    return result

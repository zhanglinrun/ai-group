from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone
from urllib.parse import urlsplit

from agents.state import AgentState
from agents.subgraphs.researcher import MAX_REACT_TURNS, ResearcherSubState, get_researcher_subgraph
from agents.tools import get_channel_registry
from agents.tools.parse_page import (
    infer_source_type,
    official_hosts_for_competitor,
    source_matches_competitor,
)
from core.config import settings
from core.defaults import DEFAULT_FOCUS_DIMENSIONS
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from models.artifact import Artifact
from models.evidence import EvidenceRecord
from models.llm_call import LLMCall
from models.run import Run
from models.step import Step
from schemas.contracts import normalize_dimension_or_none, validate_dimension, validate_source_type
from schemas.ids import make_id
from schemas.intake import category_aliases_for_target, text_mentions_any_term
from schemas.supervisor import ConductResearch, FocusDimension
from service.collector.errors import ChannelError
from service.collector.source_resolver import SourceResolutionResult, resolve_official_sources
from service.event_bus import RunEventType, emit_run_event
from service.desensitize import normalize_text_for_storage
from service.locale import source_locale
from service.llm.records import build_llm_call_record_from_mapping
from service.collector.source_quality import is_low_semantic_text, source_blocklist_reason
from utils.log_node import log_node
from utils.logger import get_logger

log = get_logger("agents.researcher")

RESEARCHER_LOW_SEMANTIC_MIN_CHARS = 140
_OFFICIAL_SOURCE_TYPES: frozenset[str] = frozenset(
    {"official_site", "official_doc", "docs", "pricing_page"}
)
_AUTHORITATIVE_REPORT_HOST_HINTS: tuple[str, ...] = (
    "caict",
    "gartner",
    "idc",
    "forrester",
    "statista",
    "questmobile",
    "iresearch",
    "analysys",
    "researchandmarkets",
)
_OFFICIAL_URL_SEARCH_QUERY_LIMIT = 2
_OFFICIAL_URL_SEARCH_MAX_RESULTS = 6
_OFFICIAL_URL_CANDIDATE_BUDGET = 8
_RESEARCHER_GENERIC_NAME_TOKENS: frozenset[str] = frozenset(
    {"ai", "app", "tool", "tools", "software", "assistant", "the", "inc", "labs", "lab"}
)
_RESEARCHER_SEGMENT_HINTS: tuple[str, ...] = (
    "眼镜",
    "glasses",
    "ar",
    "wearable",
    "可穿戴",
)


def _candidate_name_tokens(name: str) -> set[str]:
    tokens = set(re.findall(r"[a-z0-9\u4e00-\u9fff]+", name.casefold()))
    return {
        token
        for token in tokens
        if token not in _RESEARCHER_GENERIC_NAME_TOKENS and (len(token) >= 2 or not token.isascii())
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


def _coerce_intake_draft(payload: object) -> dict[str, object] | None:
    return payload if isinstance(payload, dict) else None


def _normalized_intake_string(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized if normalized else None


def _normalized_intake_reference_urls(value: object) -> list[str] | None:
    if not isinstance(value, list):
        return None
    normalized = [item.strip() for item in value if isinstance(item, str) and item.strip()]
    return normalized


def _merge_intake_context_drafts(
    *,
    state_intake_draft: dict[str, object] | None,
    persisted_intake_draft: dict[str, object] | None,
) -> dict[str, object] | None:
    merged: dict[str, object] = {}
    for field_name in (
        "domain_hint",
        "market_scope",
        "response_language",
        "target_category",
        "scope_policy",
    ):
        state_value = (
            _normalized_intake_string(state_intake_draft.get(field_name))
            if state_intake_draft is not None
            else None
        )
        persisted_value = (
            _normalized_intake_string(persisted_intake_draft.get(field_name))
            if persisted_intake_draft is not None
            else None
        )
        selected = state_value if state_value is not None else persisted_value
        if selected is not None:
            merged[field_name] = selected
    state_reference_urls = (
        _normalized_intake_reference_urls(state_intake_draft.get("reference_urls"))
        if state_intake_draft is not None
        else None
    )
    persisted_reference_urls = (
        _normalized_intake_reference_urls(persisted_intake_draft.get("reference_urls"))
        if persisted_intake_draft is not None
        else None
    )
    selected_reference_urls = (
        state_reference_urls
        if state_reference_urls
        else persisted_reference_urls
    )
    if selected_reference_urls is not None:
        merged["reference_urls"] = selected_reference_urls
    for field_name in ("category_aliases", "excluded_categories", "market_segments"):
        state_values = _intake_string_list(state_intake_draft, field_name)
        persisted_values = _intake_string_list(persisted_intake_draft, field_name)
        selected_values = state_values or persisted_values
        if selected_values:
            merged[field_name] = selected_values
    return merged if merged else None


def _intake_string_list(payload: dict[str, object] | None, field_name: str) -> list[str]:
    if payload is None:
        return []
    raw = payload.get(field_name)
    if not isinstance(raw, list):
        return []
    return [item.strip() for item in raw if isinstance(item, str) and item.strip()]


def _classify_category_relevance(
    *,
    text: str,
    target_category: str | None,
    category_aliases: list[str],
    excluded_categories: list[str],
    market_segments: list[str],
    scope_policy: str | None,
    admission_status: str | None,
) -> tuple[str, str]:
    if target_category is None:
        return "target", "no_target_category_available"
    if excluded_categories and text_mentions_any_term(text, excluded_categories):
        return "off_topic", "matched_excluded_category"
    aliases = category_aliases or category_aliases_for_target(target_category)
    if aliases and text_mentions_any_term(text, aliases):
        return "target", "matched_target_category"
    # In a broad-market landscape the vetted competitor set IS the scope, so a
    # main player's evidence is on-topic even when the snippet never repeats the
    # abstract category phrase — real product evidence (e.g. an NVIDIA GPU page)
    # almost never says "AI硬件". Literal-term matching alone was demoting/dropping
    # nearly all such evidence, collapsing a 10-competitor run to a handful of rows.
    if scope_policy == "broad_market" and admission_status == "main_player":
        return "target", "broad_market_main_player_admitted"
    if admission_status == "value_chain":
        return "value_chain", "player_admitted_as_value_chain"
    segment_terms = [*market_segments]
    if scope_policy == "broad_market":
        segment_terms.extend(_RESEARCHER_SEGMENT_HINTS)
    if segment_terms and text_mentions_any_term(text, segment_terms):
        return "adjacent_segment", "matched_adjacent_market_segment"
    if admission_status == "watchlist":
        return "unknown", "player_admitted_as_watchlist"
    # broad_market scope: keep vetted-landscape evidence as adjacent instead of
    # discarding it for lacking the literal category term, so breadth survives.
    if scope_policy == "broad_market":
        return "adjacent_segment", "broad_market_admitted_without_term"
    return "off_topic", "missing_target_category_term"


async def _load_persisted_intake_draft(
    *,
    run_id: str,
    session_factory: object,
) -> dict[str, object] | None:
    async with session_factory() as session:
        getter = getattr(session, "get", None)
        if not callable(getter):
            return None
        run_row = await getter(Run, run_id)
    if run_row is None:
        return None
    return _coerce_intake_draft(getattr(run_row, "intake_draft", None))


def _state_or_intake_string(
    state: AgentState,
    field_name: str,
    *,
    intake_draft: dict[str, object] | None = None,
) -> str | None:
    value_raw = state.get(field_name)
    if isinstance(value_raw, str) and value_raw.strip():
        return value_raw.strip()
    source_draft = intake_draft if intake_draft is not None else _coerce_intake_draft(
        state.get("intake_draft")
    )
    intake_value_raw = source_draft.get(field_name) if source_draft is not None else None
    if isinstance(intake_value_raw, str) and intake_value_raw.strip():
        return intake_value_raw.strip()
    return None


def _state_or_intake_reference_urls(
    state: AgentState,
    *,
    intake_draft: dict[str, object] | None = None,
) -> list[str]:
    reference_urls_raw = state.get("reference_urls")
    if not isinstance(reference_urls_raw, list):
        source_draft = intake_draft if intake_draft is not None else _coerce_intake_draft(
            state.get("intake_draft")
        )
        reference_urls_raw = source_draft.get("reference_urls") if source_draft is not None else None
    if not isinstance(reference_urls_raw, list):
        return []
    return [item.strip() for item in reference_urls_raw if isinstance(item, str) and item.strip()]


def _state_or_intake_string_list(
    state: AgentState,
    field_name: str,
    *,
    intake_draft: dict[str, object] | None = None,
) -> list[str]:
    raw = state.get(field_name)
    if isinstance(raw, list):
        return [item.strip() for item in raw if isinstance(item, str) and item.strip()]
    source_draft = intake_draft if intake_draft is not None else _coerce_intake_draft(
        state.get("intake_draft")
    )
    raw = source_draft.get(field_name) if source_draft is not None else None
    if not isinstance(raw, list):
        return []
    return [item.strip() for item in raw if isinstance(item, str) and item.strip()]


def _competitor_admission_statuses(state: AgentState) -> dict[str, str]:
    raw = state.get("discovered_competitor_sources")
    if not isinstance(raw, dict):
        return {}
    statuses: dict[str, str] = {}
    for competitor_id, payload in raw.items():
        if not isinstance(competitor_id, str) or not isinstance(payload, dict):
            continue
        status = payload.get("admission_status")
        if isinstance(status, str) and status.strip():
            statuses[competitor_id] = status.strip()
    return statuses


def _resolve_focus_dimensions(
    *,
    request: ConductResearch,
) -> list[FocusDimension]:
    focus_dimensions = list(request.focus_dimensions or [])
    if not focus_dimensions:
        focus_dimensions = list(DEFAULT_FOCUS_DIMENSIONS)
    if not focus_dimensions:
        raise RuntimeError(f"No focus_dimensions available for competitor_id={request.competitor_id}.")
    normalized: list[str] = []
    seen: set[str] = set()
    for dimension in focus_dimensions:
        normalized_dimension = validate_dimension(dimension)
        if normalized_dimension in seen:
            continue
        seen.add(normalized_dimension)
        normalized.append(normalized_dimension)
    return normalized


def _build_initial_substate(
    *,
    run_id: str,
    step_id: str,
    request: ConductResearch,
    focus_dimensions: list[FocusDimension],
    domain_hint: str | None,
    market_scope: str | None,
    response_language: str | None,
    reference_urls: list[str],
    resolved_official_urls: list[str],
    resolved_official_hosts: list[str],
    resolved_source_pages: list[dict[str, str]],
    search_attempts_per_dim: int,
    target_category: str | None,
    category_aliases: list[str],
    excluded_categories: list[str],
    market_segments: list[str],
    scope_policy: str | None,
) -> ResearcherSubState:
    # Reserve search_attempts_per_dim searches + 1 fetch per dimension, with the
    # tier's react_turns as the floor, so every dimension can search to budget.
    per_dim_tool_budget = search_attempts_per_dim + 1
    max_turns = max(
        request.max_iterations or MAX_REACT_TURNS,
        len(focus_dimensions) * per_dim_tool_budget,
    )
    return {
        "run_id": run_id,
        "step_id": step_id,
        "research_topic": request.research_topic,
        "competitor_id": request.competitor_id,
        "focus_dimensions": list(focus_dimensions),
        "pending_dimensions": list(focus_dimensions),
        "queried_dimensions": [],
        "pending_action_args": {},
        "turn_count": 0,
        "max_turns": max_turns,
        "search_max_results": request.search_max_results,
        "search_attempts_per_dim": search_attempts_per_dim,
        "compression_count": 0,
        "last_compressed_turn": -1,
        "messages": [],
        "observations_log": [],
        "observation_briefs": [],
        "evidence_drafts": [],
        "llm_calls": [],
        "next_action": "tool_exec",
        "final_summary": "",
        "compressed_summary": "",
        "domain_hint": domain_hint,
        "target_category": target_category,
        "category_aliases": category_aliases,
        "excluded_categories": excluded_categories,
        "market_segments": market_segments,
        "scope_policy": scope_policy,
        "market_scope": market_scope,
        "response_language": response_language,
        "reference_urls": reference_urls,
        "discovered_urls": [],
        "resolved_official_urls": resolved_official_urls,
        "resolved_official_hosts": resolved_official_hosts,
        "resolved_source_pages": resolved_source_pages,
        "search_call_count": 0,
        "official_fetch_count": 0,
        "coverage_matrix": {},
    }


def _candidate_source_urls_for_competitor(
    *,
    state: AgentState,
    competitor_id: str,
    reference_urls: list[str],
) -> list[str]:
    urls: list[str] = []
    discovered_sources_raw = state.get("discovered_competitor_sources")
    if isinstance(discovered_sources_raw, dict):
        payload = discovered_sources_raw.get(competitor_id)
        if isinstance(payload, dict):
            official_url_raw = payload.get("official_url")
            if isinstance(official_url_raw, str) and official_url_raw.strip():
                urls.append(official_url_raw.strip())
    plan_tree_raw = state.get("plan_tree")
    if isinstance(plan_tree_raw, dict):
        plan_sources_raw = plan_tree_raw.get("competitor_sources")
        if isinstance(plan_sources_raw, dict):
            plan_payload = plan_sources_raw.get(competitor_id)
            if isinstance(plan_payload, dict):
                plan_url_raw = plan_payload.get("official_url")
                if isinstance(plan_url_raw, str) and plan_url_raw.strip():
                    urls.append(plan_url_raw.strip())
    urls.extend(reference_urls)
    ordered: list[str] = []
    seen: set[str] = set()
    for item in urls:
        key = item.casefold()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(item)
    return ordered


def _official_url_search_queries(
    *,
    competitor_id: str,
    market_scope: str | None,
    response_language: str | None,
) -> list[str]:
    scope_prefix = f"{market_scope} " if market_scope else ""
    if response_language == "zh":
        candidates = [
            f"{scope_prefix}{competitor_id} 官网",
            f"{scope_prefix}{competitor_id} 官方网站",
        ]
    else:
        candidates = [
            f"{scope_prefix}{competitor_id} official site",
            f"{scope_prefix}{competitor_id} company website",
        ]
    ordered: list[str] = []
    seen: set[str] = set()
    for item in candidates:
        normalized = item.strip()
        if not normalized:
            continue
        key = normalized.casefold()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(normalized)
    return ordered[:_OFFICIAL_URL_SEARCH_QUERY_LIMIT]


def _merge_candidate_urls(urls: list[str]) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for item in urls:
        normalized = item.strip()
        if not normalized:
            continue
        key = normalized.casefold()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(normalized)
        if len(ordered) >= _OFFICIAL_URL_CANDIDATE_BUDGET:
            break
    return ordered


async def _discover_official_url_candidates(
    *,
    competitor_id: str,
    market_scope: str | None,
    response_language: str | None,
) -> list[str]:
    registry = get_channel_registry()
    candidate_urls: list[str] = []
    for query in _official_url_search_queries(
        competitor_id=competitor_id,
        market_scope=market_scope,
        response_language=response_language,
    ):
        args: dict[str, object] = {
            "query": query,
            "max_results": _OFFICIAL_URL_SEARCH_MAX_RESULTS,
        }
        if response_language is not None:
            args["response_language"] = response_language
        if market_scope is not None:
            args["market_scope"] = market_scope
        try:
            observation = await registry.invoke("search_web", args=args)
        except (ChannelError, RuntimeError, ValueError) as exc:
            log.warning(
                "researcher.official_url_search_failed",
                competitor_id=competitor_id,
                query=query,
                error_type=type(exc).__name__,
                error=str(exc)[:200],
            )
            continue
        for snippet in observation.result.snippets:
            source_url = getattr(snippet, "source_url", None)
            if isinstance(source_url, str) and source_url.strip():
                candidate_urls.append(source_url.strip())
        if len(candidate_urls) >= _OFFICIAL_URL_CANDIDATE_BUDGET:
            break
    return _merge_candidate_urls(candidate_urls)


def _build_evidence_rows(
    *,
    run_id: str,
    step_id: str,
    collected_at: datetime,
    focus_dimensions: list[FocusDimension],
    evidence_drafts: list[dict[str, object]],
    observations_log: list[dict[str, object]],
    default_competitor_id: str,
    resolved_official_hosts: set[str] | None = None,
    funnel_metrics: dict[str, object] | None = None,
    target_category: str | None = None,
    category_aliases: list[str] | None = None,
    excluded_categories: list[str] | None = None,
    market_segments: list[str] | None = None,
    scope_policy: str | None = None,
    competitor_admissions: dict[str, str] | None = None,
) -> tuple[list[EvidenceRecord], list[str], dict[str, object]]:
    dropped_reasons: dict[str, int] = {}
    funnel_overall: dict[str, int] = {
        "search_results": 0,
        "drafts": 0,
        "post_quality": 0,
        "post_grounding": 0,
        "post_rerank": 0,
        "persisted": 0,
    }
    funnel_by_competitor: dict[str, dict[str, dict[str, object]]] = {}

    def _dimension_bucket(value: str | None) -> str:
        if isinstance(value, str) and value.strip():
            return value
        return "unclassified"

    def _funnel_bucket(
        *,
        competitor_id: str,
        dimension: str | None,
    ) -> dict[str, object]:
        competitor_key = competitor_id.strip() or "unknown"
        dimension_key = _dimension_bucket(dimension)
        competitor_rows = funnel_by_competitor.setdefault(competitor_key, {})
        bucket = competitor_rows.get(dimension_key)
        if bucket is not None:
            return bucket
        created = {
            "search_results": 0,
            "drafts": 0,
            "post_quality": 0,
            "post_grounding": 0,
            "post_rerank": 0,
            "persisted": 0,
            "drop_reasons": {},
        }
        competitor_rows[dimension_key] = created
        return created

    def _record_stage(
        *,
        competitor_id: str,
        dimension: str | None,
        stage: str,
        amount: int = 1,
    ) -> None:
        if amount <= 0:
            return
        bucket = _funnel_bucket(competitor_id=competitor_id, dimension=dimension)
        stage_count_raw = bucket.get(stage)
        stage_count = stage_count_raw if isinstance(stage_count_raw, int) else 0
        bucket[stage] = stage_count + amount
        funnel_overall[stage] = funnel_overall.get(stage, 0) + amount

    def record_drop(reason: str | None) -> None:
        if reason is None:
            return
        dropped_reasons[reason] = dropped_reasons.get(reason, 0) + 1

    def record_drop_for_bucket(
        *,
        reason: str | None,
        competitor_id: str,
        dimension: str | None,
    ) -> None:
        if reason is None:
            return
        record_drop(reason)
        bucket = _funnel_bucket(competitor_id=competitor_id, dimension=dimension)
        drop_reasons_raw = bucket.get("drop_reasons")
        drop_reasons = (
            drop_reasons_raw if isinstance(drop_reasons_raw, dict) else {}
        )
        drop_reasons[reason] = int(drop_reasons.get(reason, 0)) + 1
        bucket["drop_reasons"] = drop_reasons

    def dedupe_key(
        *,
        competitor_id: str,
        dimension: str | None,
        source_url: str | None,
        quote: str,
    ) -> tuple[str, str | None, str, str]:
        normalized_quote = normalize_text_for_storage(quote)
        quote_hash = hashlib.sha256(normalized_quote.encode("utf-8")).hexdigest()[:16]
        return (
            competitor_id,
            dimension,
            normalize_text_for_storage(source_url or ""),
            quote_hash,
        )

    normalized_runtime_official_hosts = {
        host.lower().removeprefix("www.").strip()
        for host in (resolved_official_hosts or set())
        if isinstance(host, str) and host.strip()
    }

    def official_hosts_for(competitor_id: str) -> set[str]:
        if competitor_id == default_competitor_id and normalized_runtime_official_hosts:
            return set(normalized_runtime_official_hosts)
        return official_hosts_for_competitor(competitor_id)

    def source_matches_hosts(
        *,
        source_url: str | None,
        hosts: set[str],
    ) -> bool | None:
        if not source_url or not hosts:
            return None
        host = urlsplit(source_url).netloc.lower().removeprefix("www.")
        if not host:
            return None
        normalized_hosts = {item.lower().removeprefix("www.") for item in hosts}
        if host in normalized_hosts:
            return True
        if any(host.endswith(f".{item}") for item in normalized_hosts):
            return True
        return False

    def source_authority_for(
        *,
        source_url: str | None,
        source_type: str,
        competitor_source_match: bool | None,
    ) -> tuple[str, str]:
        if competitor_source_match is True and source_type in _OFFICIAL_SOURCE_TYPES:
            return "official", "competitor_official_host"
        if source_type == "market_report":
            return "authoritative_report", "market_report_source_type"
        if source_type == "public_review":
            return "public_review", "public_review_source_type"
        if source_url:
            host = urlsplit(source_url).netloc.lower().removeprefix("www.")
            if any(hint in host for hint in _AUTHORITATIVE_REPORT_HOST_HINTS):
                return "authoritative_report", "authoritative_report_host"
        return "third_party", "default_third_party"

    effective_drafts: list[dict[str, object]] = []
    seen_keys: set[tuple[str, str | None, str, str]] = set()
    for draft in evidence_drafts:
        if not isinstance(draft, dict):
            continue
        competitor_id_raw = draft.get("competitor_id")
        quote_raw = draft.get("quote")
        if not isinstance(competitor_id_raw, str) or not isinstance(quote_raw, str):
            continue
        normalized_dimension, _ = normalize_dimension_or_none(
            draft.get("dimension"),
            allowed=focus_dimensions,
        )
        source_url_raw = draft.get("source_url")
        key = dedupe_key(
            competitor_id=competitor_id_raw,
            dimension=normalized_dimension,
            source_url=source_url_raw if isinstance(source_url_raw, str) else None,
            quote=quote_raw,
        )
        if key in seen_keys:
            continue
        seen_keys.add(key)
        effective_drafts.append(draft)
        _record_stage(
            competitor_id=competitor_id_raw,
            dimension=normalized_dimension,
            stage="post_rerank",
        )

    for observation in observations_log:
        if not isinstance(observation, dict):
            continue
        result_raw = observation.get("result")
        if not isinstance(result_raw, dict):
            continue
        snippets_raw = result_raw.get("snippets")
        if not isinstance(snippets_raw, list):
            continue
        args_raw = observation.get("args")
        args = args_raw if isinstance(args_raw, dict) else {}
        fallback_dimension_raw = args.get("dimension")
        fallback_dimension = (
            fallback_dimension_raw
            if isinstance(fallback_dimension_raw, str) and fallback_dimension_raw.strip()
            else None
        )
        fallback_competitor_raw = args.get("competitor_id")
        fallback_competitor = (
            fallback_competitor_raw
            if isinstance(fallback_competitor_raw, str) and fallback_competitor_raw.strip()
            else default_competitor_id
        )
        for snippet_raw in snippets_raw:
            if not isinstance(snippet_raw, dict):
                continue
            metadata_raw = snippet_raw.get("metadata", {})
            metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
            dimension_raw = snippet_raw.get("dimension")
            if not isinstance(dimension_raw, str):
                dimension_candidate_raw = metadata.get("dimension")
                if isinstance(dimension_candidate_raw, str):
                    dimension_raw = dimension_candidate_raw
                else:
                    dimension_raw = fallback_dimension
            normalized_dimension, drop_reason = normalize_dimension_or_none(
                dimension_raw,
                allowed=focus_dimensions,
            )
            competitor_id_raw = snippet_raw.get("competitor_id")
            if not isinstance(competitor_id_raw, str):
                competitor_candidate_raw = metadata.get("competitor_id")
                if isinstance(competitor_candidate_raw, str):
                    competitor_id_raw = competitor_candidate_raw
                else:
                    competitor_id_raw = fallback_competitor
            _record_stage(
                competitor_id=competitor_id_raw,
                dimension=normalized_dimension,
                stage="search_results",
            )
            quote_candidate_raw = snippet_raw.get("quote")
            sanitized_candidate_raw = snippet_raw.get("sanitized_text")
            quote_raw: str | None = None
            if isinstance(quote_candidate_raw, str) and quote_candidate_raw.strip():
                quote_raw = quote_candidate_raw
            elif isinstance(sanitized_candidate_raw, str) and sanitized_candidate_raw.strip():
                quote_raw = sanitized_candidate_raw
            if quote_raw is None:
                record_drop_for_bucket(
                    reason="missing_quote",
                    competitor_id=competitor_id_raw,
                    dimension=normalized_dimension,
                )
                continue
            source_url_raw = snippet_raw.get("source_url")
            source_url = source_url_raw if isinstance(source_url_raw, str) else None
            key = dedupe_key(
                competitor_id=competitor_id_raw,
                dimension=normalized_dimension,
                source_url=source_url,
                quote=quote_raw,
            )
            if key in seen_keys:
                continue
            seen_keys.add(key)
            effective_drafts.append(
                {
                    "dimension": normalized_dimension,
                    "competitor_id": competitor_id_raw,
                    "quote": quote_raw,
                    "sanitized_text": snippet_raw.get("sanitized_text", quote_raw),
                    "source_type": snippet_raw.get("source_type", "article"),
                    "source_url": snippet_raw.get("source_url"),
                    "source_title": snippet_raw.get("source_title"),
                    "desensitized": snippet_raw.get("desensitized", True),
                    "metadata": {
                        **metadata,
                        "dimension_drop_reason": drop_reason,
                    },
                }
            )

    for draft in effective_drafts:
        if not isinstance(draft, dict):
            continue
        competitor_raw = draft.get("competitor_id")
        quote_raw = draft.get("quote")
        if not isinstance(competitor_raw, str) or not isinstance(quote_raw, str):
            continue
        dimension_raw, _ = normalize_dimension_or_none(
            draft.get("dimension"),
            allowed=focus_dimensions,
        )
        _record_stage(
            competitor_id=competitor_raw,
            dimension=dimension_raw,
            stage="drafts",
        )

    evidence_rows: list[EvidenceRecord] = []
    evidence_ids: list[str] = []
    floor_candidates: list[dict[str, object]] = []
    # Grounded + on-category candidates are buffered, not admitted inline, so the
    # target-evidence floor can run after every candidate is classified.
    admitted_candidates: list[tuple[str | None, str, dict[str, object]]] = []

    def append_evidence_row(candidate: dict[str, object]) -> None:
        evidence_id = make_id("ev_")
        evidence_ids.append(evidence_id)
        metadata_raw = candidate.get("metadata")
        metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
        competitor_id_raw = candidate.get("competitor_id")
        if isinstance(competitor_id_raw, str):
            _record_stage(
                competitor_id=competitor_id_raw,
                dimension=(
                    candidate.get("dimension")
                    if isinstance(candidate.get("dimension"), str)
                    else None
                ),
                stage="persisted",
            )
        evidence_rows.append(
            EvidenceRecord(
                id=evidence_id,
                run_id=run_id,
                source_type=str(candidate["source_type"]),
                source_url=(
                    candidate["source_url"] if isinstance(candidate.get("source_url"), str) else None
                ),
                source_title=(
                    candidate["source_title"] if isinstance(candidate.get("source_title"), str) else None
                ),
                quote=str(candidate["quote"]),
                sanitized_text=str(candidate["sanitized_text"]),
                span={
                    **metadata,
                    "dimension": candidate.get("dimension"),
                    "competitor_id": candidate["competitor_id"],
                },
                collected_by=step_id,
                collected_at=collected_at,
                desensitized=bool(candidate.get("desensitized", False)),
            )
        )

    def category_relevance_for_candidate(candidate: dict[str, object]) -> tuple[str, str]:
        competitor_id = str(candidate.get("competitor_id") or default_competitor_id)
        source_title = candidate.get("source_title") if isinstance(candidate.get("source_title"), str) else ""
        quote_text = candidate.get("quote") if isinstance(candidate.get("quote"), str) else ""
        sanitized_text = (
            candidate.get("sanitized_text") if isinstance(candidate.get("sanitized_text"), str) else ""
        )
        combined = f"{competitor_id} {source_title} {quote_text} {sanitized_text}"
        admission_status = (competitor_admissions or {}).get(competitor_id)
        return _classify_category_relevance(
            text=combined,
            target_category=target_category,
            category_aliases=list(category_aliases or []),
            excluded_categories=list(excluded_categories or []),
            market_segments=list(market_segments or []),
            scope_policy=scope_policy,
            admission_status=admission_status,
        )

    def source_quality_drop_reason(*, source_url: str | None, text: str) -> str | None:
        if source_blocklist_reason(source_url) is not None:
            return "source_blocklist"
        low_semantic, _ = is_low_semantic_text(
            text,
            min_chars=RESEARCHER_LOW_SEMANTIC_MIN_CHARS,
        )
        if low_semantic:
            return "low_semantic"
        return None

    for draft in effective_drafts:
        if not isinstance(draft, dict):
            continue
        dimension_raw = draft.get("dimension")
        competitor_id_raw = draft.get("competitor_id")
        quote_raw = draft.get("quote")
        sanitized_text_raw = draft.get("sanitized_text")
        source_type_raw = draft.get("source_type")
        source_url_raw = draft.get("source_url")
        source_title_raw = draft.get("source_title")
        metadata_raw = draft.get("metadata", {})
        if not isinstance(competitor_id_raw, str) or not isinstance(quote_raw, str):
            continue
        normalized_dimension, drop_reason = normalize_dimension_or_none(
            dimension_raw,
            allowed=focus_dimensions,
        )
        metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
        upstream_drop_reason = metadata.get("dimension_drop_reason")
        if isinstance(upstream_drop_reason, str) and upstream_drop_reason:
            drop_reason = upstream_drop_reason
        record_drop_for_bucket(
            reason=drop_reason,
            competitor_id=competitor_id_raw,
            dimension=normalized_dimension,
        )
        if isinstance(source_type_raw, str):
            try:
                normalized_source_type = validate_source_type(source_type_raw)
            except ValueError:
                normalized_source_type = "article"
        else:
            normalized_source_type = "article"
        sanitized_text = sanitized_text_raw if isinstance(sanitized_text_raw, str) else quote_raw
        source_url = source_url_raw if isinstance(source_url_raw, str) else None
        source_title = source_title_raw if isinstance(source_title_raw, str) else None
        quote_raw = normalize_text_for_storage(quote_raw)
        sanitized_text = normalize_text_for_storage(sanitized_text)
        if source_url is not None:
            source_url = normalize_text_for_storage(source_url)
        if source_title is not None:
            source_title = normalize_text_for_storage(source_title)
        competitor_official_hosts = official_hosts_for(competitor_id_raw)
        inferred_source_type = infer_source_type(
            source_url=source_url,
            official_hosts=competitor_official_hosts,
        )
        competitor_source_match = source_matches_hosts(
            source_url=source_url,
            hosts=competitor_official_hosts,
        )
        if competitor_source_match is None:
            competitor_source_match = source_matches_competitor(
                source_url=source_url,
                competitor_id=competitor_id_raw,
            )
        if normalized_source_type == "article" and inferred_source_type != "article":
            normalized_source_type = inferred_source_type
        elif (
            normalized_source_type in _OFFICIAL_SOURCE_TYPES
            and inferred_source_type not in _OFFICIAL_SOURCE_TYPES
        ):
            # Upstream tools classify against the union of all competitors' official
            # hosts, so a competitor's research result pointing at another vendor's
            # official domain can arrive mislabeled. Re-derive against this
            # competitor's own hosts and downgrade when it is not genuinely official.
            normalized_source_type = inferred_source_type
        source_authority, source_authority_reason = source_authority_for(
            source_url=source_url,
            source_type=normalized_source_type,
            competitor_source_match=competitor_source_match,
        )
        source_locale_payload = source_locale(
            source_url=source_url,
            span=metadata,
            sanitized_text=sanitized_text,
        )
        metadata = {
            **metadata,
            "dimension_drop_reason": drop_reason,
            "competitor_source_match": competitor_source_match,
            "source_authority": source_authority,
            "source_authority_reason": source_authority_reason,
            "source_language": source_locale_payload["language"],
            "detected_language": source_locale_payload["language"],
            "source_language_signal": source_locale_payload["language_signal"],
            "target_category": target_category,
            "category_aliases": list(category_aliases or []),
            "domain_hint_at_collection": metadata.get("domain_hint", target_category),
        }
        candidate = {
            "dimension": normalized_dimension,
            "competitor_id": competitor_id_raw,
            "quote": quote_raw,
            "sanitized_text": sanitized_text,
            "source_type": normalized_source_type,
            "source_url": source_url,
            "source_title": source_title,
            "desensitized": bool(draft.get("desensitized", False)),
            "metadata": metadata,
        }
        quality_drop_reason = source_quality_drop_reason(
            source_url=source_url,
            text=sanitized_text or quote_raw,
        )
        if quality_drop_reason is not None:
            record_drop_for_bucket(
                reason=quality_drop_reason,
                competitor_id=competitor_id_raw,
                dimension=normalized_dimension,
            )
            if quality_drop_reason in {"low_semantic", "source_blocklist"}:
                category_relevance, category_reason = category_relevance_for_candidate(candidate)
                if category_relevance == "off_topic":
                    record_drop_for_bucket(
                        reason=f"category:{category_reason}",
                        competitor_id=competitor_id_raw,
                        dimension=normalized_dimension,
                    )
                    continue
                floor_candidates.append(
                    {
                        **candidate,
                        "metadata": {
                            **metadata,
                            "category_relevance": category_relevance,
                            "category_relevance_reason": category_reason,
                            "source_quality_drop_reason": quality_drop_reason,
                            "funnel_floor_reason": quality_drop_reason,
                        },
                    }
                )
            continue
        _record_stage(
            competitor_id=competitor_id_raw,
            dimension=normalized_dimension,
            stage="post_quality",
        )
        # A page on the competitor's own official host is grounded by attribution
        # even when the body never repeats the vendor name (pricing/docs pages
        # rarely do). source_authority == "official" already required host match +
        # official source type, so this stays a hard signal, not similarity slop.
        grounded = (
            source_authority == "official"
            or _text_mentions_candidate(
                candidate_name=competitor_id_raw,
                text=sanitized_text,
            )
            or _text_mentions_candidate(
                candidate_name=competitor_id_raw,
                text=source_title,
            )
        )
        if not grounded:
            category_relevance, category_reason = category_relevance_for_candidate(candidate)
            if category_relevance == "off_topic":
                record_drop_for_bucket(
                    reason=f"category:{category_reason}",
                    competitor_id=competitor_id_raw,
                    dimension=normalized_dimension,
                )
                continue
            record_drop_for_bucket(
                reason="competitor_grounding_miss",
                competitor_id=competitor_id_raw,
                dimension=normalized_dimension,
            )
            floor_candidates.append(
                {
                    **candidate,
                    "metadata": {
                        **metadata,
                        "category_relevance": category_relevance,
                        "category_relevance_reason": category_reason,
                        "grounding_drop_reason": "competitor_grounding_miss",
                        "funnel_floor_reason": "competitor_grounding_miss",
                    },
                }
            )
            continue
        _record_stage(
            competitor_id=competitor_id_raw,
            dimension=normalized_dimension,
            stage="post_grounding",
        )
        category_relevance, category_reason = category_relevance_for_candidate(candidate)
        if category_relevance == "off_topic":
            record_drop_for_bucket(
                reason=f"category:{category_reason}",
                competitor_id=competitor_id_raw,
                dimension=normalized_dimension,
            )
            continue
        admitted_candidates.append(
            (
                normalized_dimension,
                category_relevance,
                {
                    **candidate,
                    "metadata": {
                        **metadata,
                        "category_relevance": category_relevance,
                        "category_relevance_reason": category_reason,
                    },
                },
            )
        )

    # Target-evidence floor: in an EXPLICIT single-category run an "AI glasses"
    # report must not be built mostly from adjacent-segment smartphone content, so
    # a dimension admits adjacent_segment/unknown rows only once it holds
    # CATEGORY_TARGET_EVIDENCE_FLOOR on-target rows; otherwise they are demoted to
    # the loud evidence-floor fallback. This gate is DISABLED for broad_market
    # landscapes: there breadth across vetted players IS the scope, target evidence
    # is structurally rare (product pages never repeat the abstract category term),
    # and the floor otherwise collapsed every competitor to a single row.
    category_gate_enabled = target_category is not None and scope_policy != "broad_market"
    target_floor = settings.CATEGORY_TARGET_EVIDENCE_FLOOR
    target_count_by_dimension: dict[str | None, int] = {}
    if category_gate_enabled:
        for dimension_key, relevance, _ in admitted_candidates:
            if relevance == "target":
                target_count_by_dimension[dimension_key] = (
                    target_count_by_dimension.get(dimension_key, 0) + 1
                )
    for dimension_key, relevance, admitted_candidate in admitted_candidates:
        if (
            category_gate_enabled
            and relevance in {"adjacent_segment", "unknown"}
            and target_count_by_dimension.get(dimension_key, 0) < target_floor
        ):
            competitor_for_drop = admitted_candidate.get("competitor_id")
            record_drop_for_bucket(
                reason="category:adjacent_below_target_floor",
                competitor_id=(
                    competitor_for_drop
                    if isinstance(competitor_for_drop, str)
                    else default_competitor_id
                ),
                dimension=dimension_key if isinstance(dimension_key, str) else None,
            )
            admitted_metadata_raw = admitted_candidate.get("metadata")
            admitted_metadata = (
                admitted_metadata_raw if isinstance(admitted_metadata_raw, dict) else {}
            )
            floor_candidates.append(
                {
                    **admitted_candidate,
                    "metadata": {
                        **admitted_metadata,
                        "funnel_floor_reason": "adjacent_below_target_floor",
                    },
                }
            )
            continue
        append_evidence_row(admitted_candidate)
    if not evidence_rows and floor_candidates:
        def _floor_priority(reason: str) -> int:
            # A row demoted only for the target floor is grounded and on an
            # adjacent segment, so it is the least-bad last resort when a
            # competitor otherwise produced nothing.
            if reason == "adjacent_below_target_floor":
                return 4
            if reason == "low_semantic":
                return 3
            if reason == "source_blocklist":
                return 2
            if reason == "competitor_grounding_miss":
                return 1
            return 0

        floor_candidate = max(
            floor_candidates,
            key=lambda item: (
                _floor_priority(
                    str(
                        (
                            item.get("metadata")
                            if isinstance(item.get("metadata"), dict)
                            else {}
                        ).get("funnel_floor_reason", "unknown")
                    )
                ),
                len(str(item.get("sanitized_text") or item.get("quote") or "")),
            ),
        )
        floor_metadata_raw = floor_candidate.get("metadata")
        floor_metadata = floor_metadata_raw if isinstance(floor_metadata_raw, dict) else {}
        floor_reason_raw = floor_metadata.get("funnel_floor_reason")
        floor_reason = (
            floor_reason_raw if isinstance(floor_reason_raw, str) else "unknown"
        )
        floor_competitor_raw = floor_candidate.get("competitor_id")
        floor_competitor = (
            floor_competitor_raw if isinstance(floor_competitor_raw, str) else default_competitor_id
        )
        floor_dimension_raw = floor_candidate.get("dimension")
        floor_dimension = floor_dimension_raw if isinstance(floor_dimension_raw, str) else None
        # Floor fallback means a competitor produced zero real grounded evidence:
        # surface it loudly instead of silently persisting a placeholder row.
        log.warning(
            "researcher.evidence_floor",
            competitor_id=floor_competitor,
            dimension=floor_dimension,
            floor_reason=floor_reason,
            floor_candidate_count=len(floor_candidates),
        )
        append_evidence_row(
            {
                **floor_candidate,
                "metadata": {
                    **floor_metadata,
                    "source_quality_floor": floor_reason in {"low_semantic", "source_blocklist"},
                    "grounding_floor": floor_reason == "competitor_grounding_miss",
                    "evidence_floor": True,
                    "evidence_floor_reason": floor_reason,
                },
            }
        )
    floor_count = sum(
        1
        for row in evidence_rows
        if isinstance(row.span, dict) and row.span.get("evidence_floor") is True
    )
    non_floor_grounded_count = len(evidence_rows) - floor_count
    if funnel_metrics is not None:
        funnel_metrics.clear()
        funnel_metrics.update(
            {
                "overall": funnel_overall,
                "by_competitor": funnel_by_competitor,
                "drop_reasons": dropped_reasons,
                "floor_count": floor_count,
                "non_floor_grounded_count": non_floor_grounded_count,
            }
        )
    return (
        evidence_rows,
        evidence_ids,
        {
            "count": sum(dropped_reasons.values()),
            "reasons": dropped_reasons,
        },
    )


def _build_llm_call_rows(
    *,
    step_id: str,
    llm_calls: list[dict[str, object]],
) -> list[LLMCall]:
    rows: list[LLMCall] = []
    for item in llm_calls:
        if not isinstance(item, dict):
            continue
        model_slot_raw = item.get("model_slot")
        if not isinstance(model_slot_raw, str):
            continue
        row = build_llm_call_record_from_mapping(step_id=step_id, item=item)
        if row is not None:
            rows.append(row)
    return rows


def _coverage_matrix_from_evidence_rows(
    *,
    focus_dimensions: list[FocusDimension],
    evidence_rows: list[EvidenceRecord],
) -> dict[str, dict[str, object]]:
    matrix: dict[str, dict[str, object]] = {}
    for dimension in focus_dimensions:
        relevant_rows = [
            row
            for row in evidence_rows
            if isinstance(row.span, dict)
            and row.span.get("dimension") == dimension
            and row.span.get("category_relevance") in {"target", "adjacent_segment", "value_chain", "unknown"}
        ]
        target_rows = [
            row
            for row in relevant_rows
            if isinstance(row.span, dict) and row.span.get("category_relevance") == "target"
        ]
        matrix[dimension] = {
            "covered": bool(target_rows),
            "evidence_count": len(relevant_rows),
            "target_evidence_count": len(target_rows),
            "category_relevant_evidence_count": len(relevant_rows),
            "category_relevance_distribution": {
                relevance: sum(
                    1
                    for row in relevant_rows
                    if isinstance(row.span, dict) and row.span.get("category_relevance") == relevance
                )
                for relevance in ("target", "adjacent_segment", "value_chain", "unknown")
            },
        }
    return matrix


@log_node("researcher")
async def researcher_node(state: AgentState) -> AgentState:
    run_id = state.get("run_id")
    if run_id is None:
        raise RuntimeError("AgentState.run_id is required for researcher node.")

    session_factory = get_session_factory()
    state_intake_draft = _coerce_intake_draft(state.get("intake_draft"))
    persisted_intake_draft = await _load_persisted_intake_draft(
        run_id=run_id,
        session_factory=session_factory,
    )
    intake_draft = _merge_intake_context_drafts(
        state_intake_draft=state_intake_draft,
        persisted_intake_draft=persisted_intake_draft,
    )
    request = ConductResearch.model_validate(state.get("pending_tool_args", {}))
    domain_hint = _state_or_intake_string(state, "domain_hint", intake_draft=intake_draft)
    market_scope = _state_or_intake_string(state, "market_scope", intake_draft=intake_draft)
    response_language_raw = _state_or_intake_string(
        state,
        "response_language",
        intake_draft=intake_draft,
    )
    response_language = (
        response_language_raw
        if response_language_raw in {"zh", "en"}
        else None
    )
    target_category = _state_or_intake_string(state, "target_category", intake_draft=intake_draft)
    category_aliases = _state_or_intake_string_list(
        state,
        "category_aliases",
        intake_draft=intake_draft,
    )
    excluded_categories = _state_or_intake_string_list(
        state,
        "excluded_categories",
        intake_draft=intake_draft,
    )
    market_segments = _state_or_intake_string_list(
        state,
        "market_segments",
        intake_draft=intake_draft,
    )
    scope_policy = _state_or_intake_string(state, "scope_policy", intake_draft=intake_draft)
    analysis_archetype = (
        _state_or_intake_string(state, "analysis_archetype", intake_draft=intake_draft)
        or "comparison"
    )
    category_gate_enabled = analysis_archetype == "landscape" or scope_policy == "broad_market"
    effective_target_category = target_category if category_gate_enabled else None
    effective_category_aliases = category_aliases if category_gate_enabled else []
    effective_excluded_categories = excluded_categories if category_gate_enabled else []
    effective_market_segments = market_segments if category_gate_enabled else []
    competitor_admissions = _competitor_admission_statuses(state)
    reference_urls = _state_or_intake_reference_urls(state, intake_draft=intake_draft)
    source_candidate_urls = _candidate_source_urls_for_competitor(
        state=state,
        competitor_id=request.competitor_id,
        reference_urls=reference_urls,
    )
    official_url_candidates = await _discover_official_url_candidates(
        competitor_id=request.competitor_id,
        market_scope=market_scope,
        response_language=response_language,
    )
    source_candidate_urls = _merge_candidate_urls([*source_candidate_urls, *official_url_candidates])
    try:
        resolved_sources = await resolve_official_sources(
            competitor_id=request.competitor_id,
            competitor_name=request.competitor_id,
            candidate_urls=source_candidate_urls,
        )
    except (ChannelError, RuntimeError, ValueError) as exc:
        log.warning(
            "researcher.source_resolution_failed",
            competitor_id=request.competitor_id,
            error_type=type(exc).__name__,
            error=str(exc)[:200],
            candidate_url_count=len(source_candidate_urls),
        )
        resolved_sources = SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=len(source_candidate_urls),
            validated_candidate_count=0,
        )

    focus_dimensions = _resolve_focus_dimensions(request=request)
    tier_profile = resolve_tier_profile(
        _state_or_intake_string(state, "report_depth", intake_draft=intake_draft)
    )
    step_id = make_id("step_")
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_START,
        step_id=step_id,
        payload={
            "agent_name": "researcher",
            "competitor_id": request.competitor_id,
        },
    )
    subgraph = get_researcher_subgraph()
    subgraph_input = _build_initial_substate(
        run_id=run_id,
        step_id=step_id,
        request=request,
        focus_dimensions=focus_dimensions,
        domain_hint=domain_hint,
        market_scope=market_scope,
        response_language=response_language,
        reference_urls=reference_urls,
        resolved_official_urls=list(resolved_sources.official_urls),
        resolved_official_hosts=list(resolved_sources.official_hosts),
        resolved_source_pages=[
            {
                "url": page.url,
                "source_type": page.source_type,
                "signal": page.signal,
            }
            for page in resolved_sources.key_pages
        ],
        search_attempts_per_dim=tier_profile.search_attempts_per_dim,
        target_category=effective_target_category,
        category_aliases=effective_category_aliases,
        excluded_categories=effective_excluded_categories,
        market_segments=effective_market_segments,
        scope_policy=scope_policy,
    )
    # Each ReAct turn costs ~2 super-steps (llm_decide + tool_exec); give the
    # subgraph headroom above max_turns so it self-finalizes on turn budget
    # instead of tripping LangGraph's default recursion_limit (25).
    subgraph_recursion_limit = int(subgraph_input["max_turns"]) * 4 + 10
    subgraph_output = await subgraph.ainvoke(
        subgraph_input,
        config={"recursion_limit": subgraph_recursion_limit},
    )

    collected_at = datetime.now(timezone.utc)
    evidence_funnel: dict[str, object] = {}
    evidence_rows, evidence_ids, dropped_dimensions = _build_evidence_rows(
        run_id=run_id,
        step_id=step_id,
        collected_at=collected_at,
        focus_dimensions=focus_dimensions,
        evidence_drafts=list(subgraph_output.get("evidence_drafts", [])),
        observations_log=list(subgraph_output.get("observations_log", [])),
        default_competitor_id=request.competitor_id,
        resolved_official_hosts=set(resolved_sources.official_hosts),
        funnel_metrics=evidence_funnel,
        target_category=effective_target_category,
        category_aliases=effective_category_aliases,
        excluded_categories=effective_excluded_categories,
        market_segments=effective_market_segments,
        scope_policy=scope_policy,
        competitor_admissions=competitor_admissions,
    )
    llm_call_rows = _build_llm_call_rows(
        step_id=step_id,
        llm_calls=list(subgraph_output.get("llm_calls", [])),
    )
    raw_coverage_matrix = (
        subgraph_output.get("coverage_matrix", {})
        if isinstance(subgraph_output.get("coverage_matrix", {}), dict)
        else {}
    )
    coverage_matrix = _coverage_matrix_from_evidence_rows(
        focus_dimensions=focus_dimensions,
        evidence_rows=evidence_rows,
    )
    uncovered_dimensions = [
        dimension
        for dimension, row in coverage_matrix.items()
        if isinstance(dimension, str)
        and isinstance(row, dict)
        and not bool(row.get("covered"))
    ]
    step_payload = {
        **request.model_dump(),
        "domain_hint": domain_hint,
        "target_category": target_category,
        "category_aliases": category_aliases,
        "excluded_categories": excluded_categories,
        "market_segments": market_segments,
        "scope_policy": scope_policy,
        "market_scope": market_scope,
        "response_language": response_language,
        "reference_urls": reference_urls,
        "focus_dimensions": focus_dimensions,
        "evidence_ids": evidence_ids,
        "react_turn_count": int(subgraph_output.get("turn_count", 0)),
        "compression_count": int(subgraph_output.get("compression_count", 0)),
        "queried_dimensions": list(subgraph_output.get("queried_dimensions", [])),
        "search_call_count": int(subgraph_output.get("search_call_count", 0)),
        "official_fetch_count": int(subgraph_output.get("official_fetch_count", 0)),
        "coverage_matrix": coverage_matrix,
        "raw_coverage_matrix": raw_coverage_matrix,
        "coverage_summary": {
            "covered_dimension_count": len(coverage_matrix) - len(uncovered_dimensions),
            "total_dimension_count": len(coverage_matrix),
            "uncovered_dimensions": uncovered_dimensions,
        },
        "source_resolution": {
            "candidate_url_count": len(source_candidate_urls),
            "official_url_search_candidate_count": len(official_url_candidates),
            "attempted_candidate_count": resolved_sources.attempted_candidate_count,
            "validated_candidate_count": resolved_sources.validated_candidate_count,
            "official_hosts": list(resolved_sources.official_hosts),
            "official_urls": list(resolved_sources.official_urls),
            "resolved_key_pages": [
                {
                    "url": page.url,
                    "source_type": page.source_type,
                    "signal": page.signal,
                }
                for page in resolved_sources.key_pages
            ],
        },
        "final_summary": str(subgraph_output.get("final_summary", "")),
        "dropped_dimensions": dropped_dimensions,
        "evidence_funnel": evidence_funnel,
    }
    zero_evidence = len(evidence_rows) == 0
    if zero_evidence:
        step_payload = {
            **step_payload,
            "uncovered": True,
            "degraded_reason": "researcher_zero_evidence",
        }
    log.info(
        "researcher.dimension_drops",
        run_id=run_id,
        step_id=step_id,
        dropped_dimensions=dropped_dimensions,
    )
    log.info(
        "researcher.evidence_funnel",
        run_id=run_id,
        step_id=step_id,
        competitor_id=request.competitor_id,
        funnel_overall=(
            evidence_funnel.get("overall")
            if isinstance(evidence_funnel.get("overall"), dict)
            else {}
        ),
        drop_reasons=dropped_dimensions.get("reasons", {}),
    )

    async with session_factory() as session:
        step = Step(
            step_id=step_id,
            run_id=run_id,
            agent_name="researcher",
            status="running",
            retry_count=0,
            payload=step_payload,
        )
        session.add(step)
        await session.flush()
        for evidence_row in evidence_rows:
            session.add(evidence_row)
        for llm_call_row in llm_call_rows:
            session.add(llm_call_row)
        session.add(
            Artifact(
                artifact_id=make_id("artifact_"),
                step_id=step_id,
                kind="research_fragment",
                uri=f"memory://research/{run_id}/{request.competitor_id}",
                sha256=None,
                size_bytes=None,
            )
        )
        step.status = "degraded" if zero_evidence else "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
    for evidence_row in evidence_rows:
        span = evidence_row.span if isinstance(evidence_row.span, dict) else {}
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.EVIDENCE_COLLECTED,
            step_id=step_id,
            payload={
                "evidence_id": evidence_row.id,
                "competitor_id": span.get("competitor_id"),
                "dimension": span.get("dimension"),
                "source_type": evidence_row.source_type,
                "source_title": evidence_row.source_title,
                "source_url": evidence_row.source_url,
                "desensitized": bool(evidence_row.desensitized),
            },
        )
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_FINISH,
        step_id=step_id,
        payload={
            "agent_name": "researcher",
            "status": "degraded" if zero_evidence else "completed",
            "evidence_count": len(evidence_ids),
            "competitor_id": request.competitor_id,
            "degraded_reason": "researcher_zero_evidence" if zero_evidence else None,
        },
    )

    researched_competitors = list(state.get("researched_competitors", []))
    researched_competitor_delta = (
        [] if request.competitor_id in researched_competitors else [request.competitor_id]
    )

    result: AgentState = {
        "researched_competitors": researched_competitor_delta,
        "pending_tool_args": {},
        "last_completed_node": "researcher",
        "status": "running",
    }
    if zero_evidence:
        result["researcher_degraded_competitors"] = researched_competitor_delta or [request.competitor_id]
    return result

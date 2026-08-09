from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from contextlib import nullcontext
from functools import lru_cache
import re
from typing import Any, Literal, TypedDict
from urllib.parse import urlsplit
import yaml

from langgraph.graph import END, StateGraph

from agents.tools import get_channel_registry
from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor
from agents.tools.rerank_bocha import rerank as rerank_bocha
from core.config import settings
from core.defaults import MAX_REACT_TURNS
from schemas.contracts import normalize_dimension_or_none, validate_source_type
from schemas.supervisor import FocusDimension
from service.collector.errors import ChannelError, ChannelNotRegisteredError
from service.desensitize import DesensitizeError, normalize_text_for_storage
from service.event_bus import RunEventType, emit_run_event
from service.llm.prompts import RESEARCH_PROMPT_CHAR_BUDGET, evidence_draft_refs_for_prompt
from schemas.agent_outputs import ResearcherCompressionOutput, ResearcherDecisionOutput
from service.llm import (
    RESEARCHER_COMPRESSION_PROMPT,
    RESEARCHER_SYSTEM_PROMPT,
    build_compression_fallback_user_prompt,
    build_compression_repair_user_prompt,
    build_compression_user_prompt,
    build_researcher_fallback_user_prompt,
    build_researcher_repair_user_prompt,
    build_researcher_user_prompt,
)
from service.llm.harness import complete_structured
from service.llm.response import LLMResponse
from service.skill_store import get_skill_store
from utils.logger import bind_step, get_logger

COMPRESS_AFTER_TURNS = 4
# Compression is lossy. Gate it with the same centralized prompt budget used by
# researcher prompts so we do not silently starve long-context models.
COMPRESS_AFTER_CHARS = RESEARCH_PROMPT_CHAR_BUDGET
OBSERVATIONS_FULL_RETAIN = 2
TOOL_ERROR_PREVIEW_LIMIT = 200
RERANK_DOCUMENT_BATCH_SIZE = 50
RERANK_DOCUMENT_CHAR_LIMIT = 512
# load_skill / read_skill_file are intentionally NOT researcher actions: in the
# ReAct loop they produced zero evidence (snippet_count=0) yet burned a turn per
# competitor on generic guidance. source_routing and qa_rule skills are still
# applied — read directly from the skill store by code, not via this tool.
TOOL_ACTIONS = {
    "search_web",
    "fetch_url",
    "extract_structured",
}
DIMENSIONAL_TOOL_ACTIONS = {
    "search_web",
    "fetch_url",
    "extract_structured",
}
# Follow-up tools elaborate on a page the latest search already surfaced; they
# must inherit that search's dimension, never the next pending one.
_FOLLOWUP_DIMENSIONAL_ACTIONS = {
    "fetch_url",
    "extract_structured",
}
ACTION_TO_CHANNEL = {
    "search_web": "search_web",
    "fetch_url": "fetch_url",
    "extract_structured": "extract_structured",
}
log = get_logger("agents.researcher_subgraph")

# Fields that are safe to expose in tool.start/finish event payloads.
# WHY: keep the live feed informative (query/url/skill_id) without leaking
# bulk content (raw HTML, full search results, transient sanitizer state).
_SAFE_TOOL_ARG_KEYS = (
    "query",
    "query_variants",
    "url",
    "max_results",
    "skill_id",
    "path",
    "dimension",
    "response_language",
    "market_scope",
    "country",
)

_OFFICIAL_SOURCE_TYPES: frozenset[str] = frozenset({"official_site", "docs", "pricing_page"})
_DEFAULT_SOURCE_ROUTING_ORDER: tuple[str, ...] = (
    "official_site",
    "docs",
    "pricing_page",
    "article",
    "public_review",
)
_YAML_BLOCK_PATTERN = re.compile(r"```yaml\s*(.*?)```", flags=re.IGNORECASE | re.DOTALL)
_MAX_SOURCE_FIRST_ATTEMPTS_PER_DIMENSION = 2
_MAX_EXTRACT_ONLY_ATTEMPTS_PER_DIMENSION = 1
_QUALITY_MIN_EVIDENCE_COUNT_PER_DIMENSION = 1
_QUALITY_MIN_PREFERRED_SOURCE_HITS = 1


@dataclass(frozen=True)
class _SourceRoutingRule:
    source_type: str
    priority_delta: int
    dimension_keywords: tuple[str, ...]


def _safe_tool_args_summary(args: dict[str, object]) -> dict[str, Any]:
    summary: dict[str, Any] = {}
    for key in _SAFE_TOOL_ARG_KEYS:
        if key in args and args[key] is not None:
            summary[key] = args[key]
    return summary


def _state_step_id(state: ResearcherSubState) -> str | None:
    step_id = state.get("step_id")
    return step_id if isinstance(step_id, str) and step_id.strip() else None


def _tool_result_diagnostics(observation_row: dict[str, object]) -> dict[str, object]:
    result_section = observation_row.get("result")
    if not isinstance(result_section, dict):
        return {
            "snippet_count": 0,
            "snippet_preview": None,
            "source_type_distribution": {},
        }
    snippets_section = result_section.get("snippets")
    if not isinstance(snippets_section, list):
        return {
            "snippet_count": 0,
            "snippet_preview": None,
            "source_type_distribution": {},
        }

    source_type_distribution: dict[str, int] = {}
    snippet_preview: str | None = None
    for snippet in snippets_section:
        if not isinstance(snippet, dict):
            continue
        source_type_raw = snippet.get("source_type")
        source_type = source_type_raw if isinstance(source_type_raw, str) else "unknown"
        source_type_distribution[source_type] = source_type_distribution.get(source_type, 0) + 1
        if snippet_preview is None:
            quote_raw = snippet.get("sanitized_text") or snippet.get("quote")
            if isinstance(quote_raw, str) and quote_raw.strip():
                snippet_preview = quote_raw.strip()[:TOOL_ERROR_PREVIEW_LIMIT]
    return {
        "snippet_count": len(snippets_section),
        "snippet_preview": snippet_preview,
        "source_type_distribution": source_type_distribution,
    }


class ResearcherSubState(TypedDict, total=False):
    run_id: str
    step_id: str | None
    research_topic: str
    competitor_id: str
    focus_dimensions: list[FocusDimension]
    pending_dimensions: list[FocusDimension]
    queried_dimensions: list[FocusDimension]
    pending_action_args: dict[str, object]
    turn_count: int
    max_turns: int
    search_max_results: int
    compression_count: int
    last_compressed_turn: int
    messages: list[dict[str, str]]
    observations_log: list[dict[str, object]]
    observation_briefs: list[dict[str, object]]
    evidence_drafts: list[dict[str, object]]
    llm_calls: list[dict[str, object]]
    next_action: Literal["tool_exec", "compress", "finalize"]
    final_summary: str
    compressed_summary: str
    domain_hint: str | None
    target_category: str | None
    category_aliases: list[str]
    excluded_categories: list[str]
    market_segments: list[str]
    scope_policy: str | None
    market_scope: str | None
    response_language: str | None
    reference_urls: list[str]
    discovered_urls: list[str]
    resolved_official_urls: list[str]
    resolved_official_hosts: list[str]
    resolved_source_pages: list[dict[str, str]]
    search_call_count: int
    official_fetch_count: int
    coverage_matrix: dict[str, dict[str, object]]
    rerank_reflected_dimensions: list[FocusDimension]
    search_attempts_per_dim: int


def _state_search_max_results(state: ResearcherSubState) -> int:
    max_results_raw = state.get("search_max_results")
    if isinstance(max_results_raw, int) and max_results_raw > 0:
        return min(max_results_raw, 15)
    return 5


# States built outside researcher_node (narrow unit tests) may omit the budget;
# fall back to a single search so legacy single-attempt behavior is preserved.
_FALLBACK_SEARCH_ATTEMPTS_PER_DIM = 1


def _state_search_attempts_per_dim(state: ResearcherSubState) -> int:
    value_raw = state.get("search_attempts_per_dim")
    if isinstance(value_raw, int) and value_raw > 0:
        return value_raw
    return _FALLBACK_SEARCH_ATTEMPTS_PER_DIM


def _approx_chars(messages: list[dict[str, str]]) -> int:
    return sum(len(item.get("content", "")) for item in messages)


def _classify_tool_error(exc: Exception) -> str:
    error_type = type(exc).__name__
    message = str(exc).lower()
    if error_type == "ConnectError" or "connecterror" in message or "connection" in message:
        return "connection"
    if isinstance(exc, ChannelNotRegisteredError):
        return "channel_not_registered"
    if isinstance(exc, DesensitizeError):
        return "desensitize"
    if isinstance(exc, ChannelError):
        return "channel"
    if isinstance(exc, (ValueError, TypeError)):
        return "validation"
    if isinstance(exc, RuntimeError):
        return "runtime"
    return "unknown"


def _tool_observation_log_fields(
    *,
    observation_row: dict[str, object],
    exc: Exception | None = None,
) -> dict[str, object]:
    if "error" in observation_row:
        error_text = str(observation_row.get("error", ""))
        error_class = _classify_tool_error(exc) if exc is not None else "unknown"
        if error_class == "unknown":
            error_class = _classify_tool_error(RuntimeError(error_text))
        return {
            "success": False,
            "error_class": error_class,
            "error_preview": error_text[:TOOL_ERROR_PREVIEW_LIMIT],
        }
    return {"success": True, "error_class": None, "error_preview": None}


def _build_observation_brief(
    *,
    tool: str,
    args: dict[str, object],
    observation_row: dict[str, object],
    dimension: str | None,
) -> dict[str, object]:
    brief: dict[str, object] = {
        "tool": tool,
        "dimension": dimension if dimension is not None else args.get("dimension"),
    }
    url_raw = args.get("url")
    if isinstance(url_raw, str) and url_raw.strip():
        brief["url"] = url_raw.strip()

    if "error" in observation_row:
        brief["error_preview"] = str(observation_row.get("error", ""))[:TOOL_ERROR_PREVIEW_LIMIT]
        return brief

    result_section = observation_row.get("result")
    if not isinstance(result_section, dict):
        return brief

    snippets_section = result_section.get("snippets")
    if not isinstance(snippets_section, list):
        return brief

    brief["snippet_count"] = len(snippets_section)
    previews: list[str] = []
    for snippet in snippets_section[:3]:
        if not isinstance(snippet, dict):
            continue
        quote_raw = snippet.get("quote") or snippet.get("sanitized_text")
        if isinstance(quote_raw, str) and quote_raw.strip():
            previews.append(quote_raw.strip()[:TOOL_ERROR_PREVIEW_LIMIT])
    if previews:
        brief["quote_preview"] = " | ".join(previews)[:TOOL_ERROR_PREVIEW_LIMIT]
    return brief


def _extract_urls_from_observation(observation_row: dict[str, object]) -> list[str]:
    result_section = observation_row.get("result")
    if not isinstance(result_section, dict):
        return []
    snippets_section = result_section.get("snippets")
    if not isinstance(snippets_section, list):
        return []
    urls: list[str] = []
    for snippet in snippets_section:
        if not isinstance(snippet, dict):
            continue
        source_url = snippet.get("source_url")
        if isinstance(source_url, str) and source_url.strip():
            urls.append(source_url.strip())
    return urls


def _merge_discovered_urls(existing: list[str], new_urls: list[str]) -> list[str]:
    merged = list(existing)
    seen = set(merged)
    for url in new_urls:
        if url not in seen:
            merged.append(url)
            seen.add(url)
    return merged


def _extract_source_routing_payload(markdown: str) -> dict[str, object] | None:
    match = _YAML_BLOCK_PATTERN.search(markdown)
    if match is None:
        return None
    try:
        loaded = yaml.safe_load(match.group(1))
    except yaml.YAMLError:
        return None
    if isinstance(loaded, dict):
        return loaded
    return None


def _normalize_dimension_keywords(value: object) -> tuple[str, ...]:
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return ()
    ordered: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str):
            continue
        lowered = item.strip().casefold()
        if not lowered or lowered in seen:
            continue
        seen.add(lowered)
        ordered.append(lowered)
    return tuple(ordered)


def _load_source_routing_rules() -> list[_SourceRoutingRule]:
    store = get_skill_store()
    rules: list[_SourceRoutingRule] = []
    for skill_name in store.list_by_applies_to("source_routing"):
        parsed = store.load(skill_name)
        if parsed is None:
            continue
        payload = _extract_source_routing_payload(parsed.content)
        if payload is None:
            continue
        source_type_raw = payload.get("source_type")
        if not isinstance(source_type_raw, str) or not source_type_raw.strip():
            continue
        try:
            source_type = validate_source_type(source_type_raw.strip())
        except ValueError:
            continue
        priority_delta_raw = payload.get("priority_delta", 0)
        priority_delta: int
        if isinstance(priority_delta_raw, int):
            priority_delta = priority_delta_raw
        elif isinstance(priority_delta_raw, float):
            priority_delta = int(priority_delta_raw)
        elif (
            isinstance(priority_delta_raw, str)
            and priority_delta_raw.strip()
            and priority_delta_raw.strip().lstrip("-").isdigit()
        ):
            priority_delta = int(priority_delta_raw.strip())
        else:
            continue
        dimension_keywords = _normalize_dimension_keywords(
            payload.get("dimension_keywords", payload.get("dimension_contains"))
        )
        if not dimension_keywords:
            dimension_keywords = _normalize_dimension_keywords(list(parsed.metadata.tags))
        rules.append(
            _SourceRoutingRule(
                source_type=source_type,
                priority_delta=priority_delta,
                dimension_keywords=dimension_keywords,
            )
        )
    return rules


def _default_source_order_for_dimension(dimension: str | None) -> tuple[str, ...]:
    if not isinstance(dimension, str):
        return _DEFAULT_SOURCE_ROUTING_ORDER
    lowered = dimension.casefold()
    if any(keyword in lowered for keyword in ("pricing", "plan", "billing")):
        return ("pricing_page", "official_site", "docs", "article", "public_review")
    if any(keyword in lowered for keyword in ("feature", "capability", "integration", "tech", "api")):
        return ("docs", "official_site", "pricing_page", "article", "public_review")
    if any(keyword in lowered for keyword in ("security", "compliance", "enterprise")):
        return ("official_site", "docs", "pricing_page", "article", "public_review")
    if _is_feedback_dimension(lowered):
        return ("public_review", "article", "official_site", "docs", "pricing_page")
    return _DEFAULT_SOURCE_ROUTING_ORDER


_FEEDBACK_DIMENSION_KEYWORDS: tuple[str, ...] = ("feedback", "review", "sentiment", "persona")


def _is_feedback_dimension(dimension: str | None) -> bool:
    if not isinstance(dimension, str):
        return False
    lowered = dimension.casefold()
    return any(keyword in lowered for keyword in _FEEDBACK_DIMENSION_KEYWORDS)


def _rule_matches_dimension(*, rule: _SourceRoutingRule, dimension: str | None) -> bool:
    if not rule.dimension_keywords:
        return True
    if not isinstance(dimension, str):
        return False
    lowered = dimension.casefold()
    return any(keyword in lowered for keyword in rule.dimension_keywords)


def _source_type_priority_table_for_dimension(dimension: str | None) -> dict[str, int]:
    ordered_defaults = _default_source_order_for_dimension(dimension)
    total = len(ordered_defaults)
    priorities: dict[str, int] = {
        source_type: (total - index) * 10
        for index, source_type in enumerate(ordered_defaults)
    }
    for rule in _load_source_routing_rules():
        if not _rule_matches_dimension(rule=rule, dimension=dimension):
            continue
        priorities[rule.source_type] = priorities.get(rule.source_type, 0) + (rule.priority_delta * 10)
    return priorities


def _ordered_source_types_for_dimension(dimension: str | None) -> list[str]:
    defaults = _default_source_order_for_dimension(dimension)
    priorities = _source_type_priority_table_for_dimension(dimension)
    tie_breaker: dict[str, int] = {
        source_type: index
        for index, source_type in enumerate(defaults)
    }
    candidates = list(priorities.keys())
    for source_type in _DEFAULT_SOURCE_ROUTING_ORDER:
        if source_type not in priorities:
            candidates.append(source_type)
    return sorted(
        candidates,
        key=lambda source_type: (
            -priorities.get(source_type, 0),
            tie_breaker.get(source_type, len(tie_breaker)),
            source_type,
        ),
    )


def _normalize_source_type(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        return "article"
    try:
        return validate_source_type(value.strip())
    except ValueError:
        return "article"


def _routing_priority_for_source(
    *,
    dimension: str | None,
    source_type: str,
) -> int:
    priorities = _source_type_priority_table_for_dimension(dimension)
    return priorities.get(source_type, 0)


def _build_coverage_matrix(
    *,
    state: ResearcherSubState,
    evidence_drafts: list[dict[str, object]],
) -> dict[str, dict[str, object]]:
    focus_dimensions = list(state.get("focus_dimensions", []))
    official_hosts = _state_official_hosts(state)
    matrix: dict[str, dict[str, object]] = {}
    for dimension in focus_dimensions:
        evidence_count = 0
        official_evidence_count = 0
        public_review_count = 0
        rerank_scored_count = 0
        rerank_high_score_count = 0
        preferred_source_types = _ordered_source_types_for_dimension(dimension)[:2]
        preferred_source_hit_count = 0
        for draft in evidence_drafts:
            if _evidence_draft_dimension(draft, allowed=focus_dimensions) != dimension:
                continue
            evidence_count += 1
            source_type = _normalize_source_type(draft.get("source_type"))
            source_url_raw = draft.get("source_url")
            metadata_raw = draft.get("metadata", {})
            metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
            rerank_score_raw = metadata.get("rerank_score")
            if source_type in preferred_source_types:
                preferred_source_hit_count += 1
            if source_type == "public_review":
                public_review_count += 1
            if source_type in _OFFICIAL_SOURCE_TYPES:
                official_evidence_count += 1
            elif (
                isinstance(source_url_raw, str)
                and source_url_raw.strip()
                and _url_host_matches(source_url_raw.strip(), official_hosts)
            ):
                official_evidence_count += 1
            if isinstance(rerank_score_raw, (int, float)):
                rerank_scored_count += 1
                if float(rerank_score_raw) >= settings.RERANK_COVERAGE_THRESHOLD:
                    rerank_high_score_count += 1
        requires_official = _is_official_priority_dimension(dimension)
        requires_public_review = _is_feedback_dimension(dimension)
        evidence_count_pass = evidence_count >= _QUALITY_MIN_EVIDENCE_COUNT_PER_DIMENSION
        preferred_source_pass = preferred_source_hit_count >= _QUALITY_MIN_PREFERRED_SOURCE_HITS
        official_pass = official_evidence_count > 0 if requires_official else True
        public_review_pass = public_review_count > 0 if requires_public_review else True
        rerank_pass = (
            rerank_high_score_count >= max(1, settings.RERANK_MIN_HIGH_SCORE_PER_DIM)
            if rerank_scored_count > 0
            else True
        )
        covered = (
            evidence_count_pass
            and preferred_source_pass
            and official_pass
            and public_review_pass
            and rerank_pass
        )
        matrix[dimension] = {
            "covered": covered,
            "evidence_count": evidence_count,
            "official_evidence_count": official_evidence_count,
            "requires_official": requires_official,
            "requires_public_review": requires_public_review,
            "public_review_count": public_review_count,
            "preferred_source_types": preferred_source_types,
            "preferred_source_hit_count": preferred_source_hit_count,
            "min_evidence_required": _QUALITY_MIN_EVIDENCE_COUNT_PER_DIMENSION,
            "min_preferred_source_hits_required": _QUALITY_MIN_PREFERRED_SOURCE_HITS,
            "evidence_count_pass": evidence_count_pass,
            "preferred_source_pass": preferred_source_pass,
            "official_pass": official_pass,
            "public_review_pass": public_review_pass,
            "rerank_scored_count": rerank_scored_count,
            "rerank_high_score_count": rerank_high_score_count,
            "rerank_pass": rerank_pass,
            "rerank_coverage_threshold": settings.RERANK_COVERAGE_THRESHOLD,
        }
    return matrix


def _pending_dimensions_from_coverage(
    *,
    focus_dimensions: list[FocusDimension],
    coverage_matrix: dict[str, dict[str, object]],
    state: ResearcherSubState | None = None,
) -> list[FocusDimension]:
    pending: list[FocusDimension] = []
    original_index: dict[str, int] = {dimension: index for index, dimension in enumerate(focus_dimensions)}
    for dimension in focus_dimensions:
        row = coverage_matrix.get(dimension, {})
        covered = bool(row.get("covered"))
        evidence_count_raw = row.get("evidence_count", 0)
        evidence_count = evidence_count_raw if isinstance(evidence_count_raw, int) else 0
        allow_feedback_exhaustion = not _is_feedback_dimension(dimension) or evidence_count > 0
        if not covered and state is not None:
            extract_attempt_count = _dimension_tool_attempt_count(
                state=state,
                tool_name="extract_structured",
                dimension=dimension,
            )
            search_attempt_count = _dimension_tool_attempt_count(
                state=state,
                tool_name="search_web",
                dimension=dimension,
            )
            fetch_attempt_count = _dimension_tool_attempt_count(
                state=state,
                tool_name="fetch_url",
                dimension=dimension,
            )
            # Stop a dimension once it yields >=1 evidence draft; otherwise keep
            # searching until the per-dimension attempt budget is spent. Never
            # give up after a single search (the old "1 search + 2 fetch" rule
            # converged far too early and starved zero-evidence dimensions).
            search_attempts_budget = _state_search_attempts_per_dim(state)
            if allow_feedback_exhaustion and (
                evidence_count >= _QUALITY_MIN_EVIDENCE_COUNT_PER_DIMENSION
                or search_attempt_count >= search_attempts_budget
            ):
                covered = True
            # Deterministic/offline test mode can emit extract-only traces without
            # any fetch/search actions. Stop after repeated identical extraction
            # attempts so the run can progress to remaining dimensions.
            if (
                allow_feedback_exhaustion
                and
                not covered
                and search_attempt_count == 0
                and fetch_attempt_count == 0
                and extract_attempt_count >= _MAX_EXTRACT_ONLY_ATTEMPTS_PER_DIMENSION
            ):
                covered = True
        if not covered:
            pending.append(dimension)
    if state is not None:
        pending.sort(
            key=lambda dimension: (
                _dimension_tool_attempt_count(
                    state=state,
                    tool_name="extract_structured",
                    dimension=dimension,
                )
                + _dimension_tool_attempt_count(
                    state=state,
                    tool_name="search_web",
                    dimension=dimension,
                )
                + _dimension_tool_attempt_count(
                    state=state,
                    tool_name="fetch_url",
                    dimension=dimension,
                ),
                original_index.get(dimension, len(original_index)),
            )
        )
    return pending


def _archive_observations_log(observations_log: list[dict[str, object]]) -> list[dict[str, object]]:
    if len(observations_log) <= OBSERVATIONS_FULL_RETAIN:
        return observations_log
    archived: list[dict[str, object]] = []
    cutoff = len(observations_log) - OBSERVATIONS_FULL_RETAIN
    for index, item in enumerate(observations_log):
        if index < cutoff and isinstance(item, dict):
            args_raw = item.get("args", {})
            args = args_raw if isinstance(args_raw, dict) else {}
            archived.append(
                {
                    "tool": item.get("tool"),
                    "archived": True,
                    "dimension": args.get("dimension"),
                    "url": args.get("url"),
                    "snippet_count": (
                        len(item["result"]["snippets"])
                        if isinstance(item.get("result"), dict)
                        and isinstance(item["result"].get("snippets"), list)
                        else 0
                    ),
                    "error_preview": (
                        str(item.get("error"))[:TOOL_ERROR_PREVIEW_LIMIT]
                        if "error" in item
                        else None
                    ),
                }
            )
            continue
        archived.append(item)
    return archived


def _effective_prompt_size(state: ResearcherSubState) -> int:
    briefs = list(state.get("observation_briefs", []))
    compressed_summary = state.get("compressed_summary", "")
    messages = list(state.get("messages", []))
    evidence_refs = evidence_draft_refs_for_prompt(list(state.get("evidence_drafts", [])))
    size = len(compressed_summary) if isinstance(compressed_summary, str) else 0
    size += _approx_chars(messages)
    size += len(json.dumps(briefs[-6:], ensure_ascii=False))
    size += len(json.dumps(evidence_refs[-8:], ensure_ascii=False))
    return size


def _recent_search_dimension(state: ResearcherSubState) -> str | None:
    focus_dimensions = list(state.get("focus_dimensions", []))
    for item in reversed(list(state.get("observations_log", []))):
        if not isinstance(item, dict) or item.get("tool") != "search_web":
            continue
        args_raw = item.get("args")
        args = args_raw if isinstance(args_raw, dict) else {}
        dimension_raw = args.get("dimension")
        normalized, _ = normalize_dimension_or_none(
            dimension_raw,
            allowed=focus_dimensions,
        )
        if normalized is not None:
            return normalized
    return None


def _effective_action_dimension(
    *,
    state: ResearcherSubState,
    action_args: dict[str, object],
    action: str,
) -> str | None:
    focus_dimensions = list(state.get("focus_dimensions", []))
    dimension_raw = action_args.get("dimension")
    normalized, _ = normalize_dimension_or_none(
        dimension_raw,
        allowed=focus_dimensions,
    )
    if normalized is not None:
        return normalized
    if action in _FOLLOWUP_DIMENSIONAL_ACTIONS:
        return _recent_search_dimension(state)
    pending_dimensions = list(state.get("pending_dimensions", []))
    if pending_dimensions:
        return pending_dimensions[0]
    return _recent_search_dimension(state)



def _dimension_tool_attempt_count(
    *,
    state: ResearcherSubState,
    tool_name: str,
    dimension: FocusDimension,
) -> int:
    count = 0
    for item in list(state.get("observations_log", [])):
        if not isinstance(item, dict):
            continue
        if item.get("tool") != tool_name:
            continue
        args_raw = item.get("args", {})
        args = args_raw if isinstance(args_raw, dict) else {}
        action_dimension = args.get("dimension")
        normalized_dimension, _ = normalize_dimension_or_none(
            action_dimension,
            allowed=list(state.get("focus_dimensions", [])),
        )
        if normalized_dimension == dimension:
            count += 1
    return count


def _already_fetched_urls(state: ResearcherSubState) -> set[str]:
    """URLs already passed to fetch_url in this researcher run (any dimension).

    fetch_url falls back to a short search snippet when full-text extraction
    fails (Tavily exhausted / site blocks scraping). Without this set the LLM and
    the deepen guard re-fetch the same dead URL every turn, burning the turn
    budget and re-admitting identical boilerplate as evidence.
    """
    fetched: set[str] = set()
    for item in list(state.get("observations_log", [])):
        if not isinstance(item, dict) or item.get("tool") != "fetch_url":
            continue
        args_raw = item.get("args", {})
        args = args_raw if isinstance(args_raw, dict) else {}
        url_raw = args.get("url")
        if isinstance(url_raw, str) and url_raw.strip():
            fetched.add(url_raw.strip())
    return fetched


def _fallback_action(state: ResearcherSubState) -> tuple[str, dict[str, object]]:
    pending_dimensions = list(state.get("pending_dimensions", []))
    if not pending_dimensions:
        return ("finalize", {"summary": "fallback finalize after pending dimensions exhausted"})

    dimension = pending_dimensions[0]
    domain_hint_raw = state.get("domain_hint")
    domain_hint = (
        domain_hint_raw.strip()
        if isinstance(domain_hint_raw, str) and domain_hint_raw.strip()
        else ""
    )
    already_fetched = _already_fetched_urls(state)

    fetch_attempt_count = _dimension_tool_attempt_count(
        state=state,
        tool_name="fetch_url",
        dimension=dimension,
    )
    search_attempt_count = _dimension_tool_attempt_count(
        state=state,
        tool_name="search_web",
        dimension=dimension,
    )

    # Source-first: attempt a deterministic source fetch before open web search, but
    # never re-fetch a URL already pulled this run (a dead "official" page resolves
    # the same junk every dimension and starves the competitor of real evidence).
    if fetch_attempt_count == 0:
        official_fetch_url = _fallback_fetch_url(
            state=state,
            dimension=dimension,
            official_only=True,
            exclude_urls=already_fetched,
        )
        if official_fetch_url is not None:
            return (
                "fetch_url",
                {
                    "url": official_fetch_url,
                    "competitor_id": state["competitor_id"],
                    "dimension": dimension,
                },
            )

    if search_attempt_count < _state_search_attempts_per_dim(state):
        search_max_results = _state_search_max_results(state)
        query_prefix = f"{domain_hint} " if domain_hint else ""
        base_query = f"{query_prefix}{state['competitor_id']} {dimension} {state['research_topic']}"
        query = base_query
        if _is_official_priority_dimension(dimension):
            primary_host = _primary_official_host(state)
            if primary_host is not None:
                query = f"site:{primary_host} {state['competitor_id']} {dimension}"
        query_variants = _fallback_query_variants(
            state=state,
            dimension=dimension,
            primary_query=query,
            base_query=base_query,
        )
        return (
            "search_web",
            {
                "query": query,
                "query_variants": query_variants,
                "max_results": search_max_results,
                "dimension": dimension,
            },
        )

    # After one search round, try one more fetch pass (can use newly discovered URLs).
    if (
        fetch_attempt_count <= search_attempt_count
        and fetch_attempt_count < _MAX_SOURCE_FIRST_ATTEMPTS_PER_DIMENSION
    ):
        follow_up_fetch_url = _fallback_fetch_url(
            state=state,
            dimension=dimension,
            official_only=False,
            exclude_urls=already_fetched,
        )
        if follow_up_fetch_url is not None:
            return (
                "fetch_url",
                {
                    "url": follow_up_fetch_url,
                    "competitor_id": state["competitor_id"],
                    "dimension": dimension,
                },
            )
    return ("finalize", {"summary": "fallback finalize after online attempts exhausted"})


# Dimensions where third-party articles are not trustworthy enough for a buyer:
# pricing, enterprise readiness, security, and compliance must be sourced from the
# vendor's own pages first (R10).
_OFFICIAL_PRIORITY_DIMENSION_KEYWORDS: tuple[str, ...] = (
    "pricing",
    "enterprise",
    "compliance",
    "security",
)


def _is_official_priority_dimension(dimension: str) -> bool:
    lowered = dimension.lower()
    return any(keyword in lowered for keyword in _OFFICIAL_PRIORITY_DIMENSION_KEYWORDS)


def _state_official_hosts(state: ResearcherSubState) -> set[str]:
    resolved_hosts_raw = state.get("resolved_official_hosts", [])
    resolved_hosts = {
        item.strip()
        for item in resolved_hosts_raw
        if isinstance(item, str) and item.strip()
    }
    if resolved_hosts:
        return resolved_hosts
    competitor_id = state.get("competitor_id")
    return official_hosts_for_competitor(competitor_id if isinstance(competitor_id, str) else None)


def _primary_official_host(state: ResearcherSubState) -> str | None:
    hosts = _state_official_hosts(state)
    if not hosts:
        return None
    # Shortest host is the most likely apex domain (cursor.com over docs.cursor.com).
    return min(hosts, key=len)


def _fallback_query_variants(
    *,
    state: ResearcherSubState,
    dimension: str,
    primary_query: str,
    base_query: str,
) -> list[str]:
    competitor_id = state.get("competitor_id")
    competitor = competitor_id if isinstance(competitor_id, str) else ""
    response_language = state.get("response_language")
    market_scope_raw = state.get("market_scope")
    market_scope = market_scope_raw.strip() if isinstance(market_scope_raw, str) else ""
    primary_host = _primary_official_host(state)
    candidates = [primary_query, base_query]
    feedback_query: str | None = None
    if _is_feedback_dimension(dimension):
        if response_language == "zh":
            scope_prefix = f"{market_scope} " if market_scope else "中文 国内 "
            feedback_query = f"{scope_prefix}{competitor} 评价 口碑 优缺点 用户反馈"
        else:
            scope_prefix = f"{market_scope} " if market_scope else ""
            feedback_query = f"{scope_prefix}{competitor} reviews pros cons user feedback"
    if feedback_query is not None:
        candidates.insert(0, feedback_query)
    if primary_host is not None:
        candidates.append(f"site:{primary_host} {competitor} {dimension}")
    if response_language == "zh":
        scope_prefix = f"{market_scope} " if market_scope else "中文 国内 "
        candidates.append(f"{scope_prefix}{competitor} {dimension} 评测 对比")
    else:
        scope_prefix = f"{market_scope} " if market_scope else ""
        candidates.append(f"{scope_prefix}{competitor} {dimension} reviews comparison")
    seen: set[str] = set()
    out: list[str] = []
    for item in candidates:
        cleaned = item.strip()
        key = cleaned.casefold()
        if not cleaned or key in seen:
            continue
        seen.add(key)
        out.append(cleaned)
    return out[:3]


def _url_host_matches(url: str, official_hosts: set[str]) -> bool:
    parsed = re.sub(r"^www\.", "", urlsplit(url).netloc.lower())
    if not parsed:
        return False
    normalized_hosts = {re.sub(r"^www\.", "", item.lower()) for item in official_hosts}
    return any(parsed == host or parsed.endswith(f".{host}") for host in normalized_hosts)


def _pick_url_for_dimension(
    urls: list[str],
    dimension: FocusDimension,
    *,
    official_hosts: set[str] | None = None,
) -> str | None:
    if not urls:
        return None
    dimension_lower = dimension.lower()
    # High-risk dimensions: prefer the vendor's own domain over any third-party URL.
    if official_hosts and _is_official_priority_dimension(dimension):
        for url in urls:
            if _url_host_matches(url, official_hosts):
                return url
    if "pricing" in dimension_lower:
        for url in urls:
            lowered = url.lower()
            if "pricing" in lowered or "plan" in lowered:
                return url
    if "feature" in dimension_lower or "tech" in dimension_lower or "integration" in dimension_lower:
        for url in urls:
            lowered = url.lower()
            if "docs" in lowered or "help" in lowered:
                return url
    return urls[0]


def _fallback_fetch_url(
    *,
    state: ResearcherSubState,
    dimension: FocusDimension,
    official_only: bool = False,
    exclude_urls: set[str] | None = None,
) -> str | None:
    official_hosts = _state_official_hosts(state)
    excluded = exclude_urls or set()

    candidate_rows: list[tuple[str, str]] = []
    seen_urls: set[str] = set()

    def append_candidate(url: str, source_type: str) -> None:
        cleaned = url.strip()
        if not cleaned or cleaned in seen_urls:
            return
        if cleaned in excluded:
            return
        if official_only and not _url_host_matches(cleaned, official_hosts):
            return
        seen_urls.add(cleaned)
        candidate_rows.append((cleaned, _normalize_source_type(source_type)))

    resolved_source_pages_raw = state.get("resolved_source_pages", [])
    if isinstance(resolved_source_pages_raw, list):
        for page in resolved_source_pages_raw:
            if not isinstance(page, dict):
                continue
            url_raw = page.get("url")
            if not isinstance(url_raw, str) or not url_raw.strip():
                continue
            append_candidate(url_raw, str(page.get("source_type", "official_site")))

    resolved_official_urls_raw = state.get("resolved_official_urls", [])
    if isinstance(resolved_official_urls_raw, list):
        for url_raw in resolved_official_urls_raw:
            if not isinstance(url_raw, str) or not url_raw.strip():
                continue
            inferred_source_type = infer_source_type(source_url=url_raw, official_hosts=official_hosts)
            append_candidate(url_raw, inferred_source_type)

    reference_urls_raw = state.get("reference_urls", [])
    if isinstance(reference_urls_raw, list):
        for url_raw in reference_urls_raw:
            if not isinstance(url_raw, str) or not url_raw.strip():
                continue
            inferred_source_type = infer_source_type(source_url=url_raw, official_hosts=official_hosts)
            append_candidate(url_raw, inferred_source_type)

    discovered_urls_raw = state.get("discovered_urls", [])
    if isinstance(discovered_urls_raw, list):
        for url_raw in discovered_urls_raw:
            if not isinstance(url_raw, str) or not url_raw.strip():
                continue
            inferred_source_type = infer_source_type(source_url=url_raw, official_hosts=official_hosts)
            append_candidate(url_raw, inferred_source_type)

    if not candidate_rows:
        return None

    for source_type in _ordered_source_types_for_dimension(dimension):
        typed_urls = [url for url, candidate_source_type in candidate_rows if candidate_source_type == source_type]
        selected = _pick_url_for_dimension(
            typed_urls,
            dimension,
            official_hosts=official_hosts,
        )
        if selected is not None:
            return selected

    return _pick_url_for_dimension(
        [url for url, _ in candidate_rows],
        dimension,
        official_hosts=official_hosts,
    )


def _source_first_fetch_guard_url(
    *,
    state: ResearcherSubState,
    dimension: FocusDimension,
) -> str | None:
    if (
        _dimension_tool_attempt_count(
            state=state,
            tool_name="fetch_url",
            dimension=dimension,
        )
        > 0
    ):
        return None
    # When a competitor's only "official" host was already pulled (often a
    # mis-resolved news/aggregator page that yields no full text), do not keep
    # hijacking every dimension's first search into a re-fetch of that dead URL.
    # Returning None lets the LLM's open-web search proceed, which is the only way
    # starved competitors (1 evidence row) gather real evidence.
    return _fallback_fetch_url(
        state=state,
        dimension=dimension,
        official_only=True,
        exclude_urls=_already_fetched_urls(state),
    )


# A search-provider summary maxes out around ~800 chars and routinely ends
# mid-sentence; a fetched article body runs into the thousands. Anything at or
# above this is treated as already-deep evidence so the deepen guard does not
# re-fetch full-text pages, while shallow search summaries get deepened once.
DEEP_EVIDENCE_MIN_CHARS = 1200


def _dimension_evidence_depth(
    *,
    state: ResearcherSubState,
    dimension: FocusDimension,
) -> tuple[int, int, str | None]:
    """Return (shallow_count, deep_count, best_shallow_url) for one dimension.

    Shallow = a search-provider summary/snippet kept verbatim as a draft; deep =
    a fetched/extracted body. best_shallow_url is the source URL of the first
    shallow draft worth deepening into full text.
    """
    focus_dimensions = list(state.get("focus_dimensions", []))
    shallow_count = 0
    deep_count = 0
    best_shallow_url: str | None = None
    for draft in state.get("evidence_drafts", []):
        if not isinstance(draft, dict):
            continue
        if _evidence_draft_dimension(draft, allowed=focus_dimensions) != dimension:
            continue
        text_raw = draft.get("sanitized_text") or draft.get("quote") or ""
        text = text_raw if isinstance(text_raw, str) else ""
        if len(text) >= DEEP_EVIDENCE_MIN_CHARS:
            deep_count += 1
            continue
        shallow_count += 1
        if best_shallow_url is not None:
            continue
        source_url_raw = draft.get("source_url")
        if isinstance(source_url_raw, str) and source_url_raw.strip().startswith(
            ("http://", "https://")
        ):
            best_shallow_url = source_url_raw.strip()
    return shallow_count, deep_count, best_shallow_url


def _shallow_evidence_deepen_action(
    state: ResearcherSubState,
) -> tuple[str, dict[str, object]] | None:
    """Force one full-text fetch when a dimension only holds shallow summaries.

    Search-provider summaries were previously admitted as terminal evidence, so
    high-value third-party hits never got deepened into full article bodies. This
    upgrades the best shallow hit per dimension exactly once (bounded by the
    per-dimension fetch attempt counter), covering third-party pages too — not
    just official hosts like the source-first guard.
    """
    competitor_id_raw = state.get("competitor_id")
    if not isinstance(competitor_id_raw, str) or not competitor_id_raw.strip():
        return None
    focus_dimensions = list(state.get("focus_dimensions", []))
    pending_dimensions = list(state.get("pending_dimensions", []))
    ordered = pending_dimensions + [
        dimension for dimension in focus_dimensions if dimension not in pending_dimensions
    ]
    already_fetched = _already_fetched_urls(state)
    for dimension in ordered:
        if _is_feedback_dimension(dimension):
            continue
        if (
            _dimension_tool_attempt_count(
                state=state,
                tool_name="fetch_url",
                dimension=dimension,
            )
            > 0
        ):
            continue
        shallow_count, deep_count, best_shallow_url = _dimension_evidence_depth(
            state=state,
            dimension=dimension,
        )
        if deep_count > 0 or shallow_count == 0 or best_shallow_url is None:
            continue
        # Deepening a URL that already came back shallow just re-admits identical
        # fallback junk; only deepen URLs not yet fetched in this run.
        if best_shallow_url in already_fetched:
            continue
        return (
            "fetch_url",
            {
                "url": best_shallow_url,
                "competitor_id": competitor_id_raw.strip(),
                "dimension": dimension,
            },
        )
    return None


def _needs_compress(state: ResearcherSubState) -> bool:
    turn_count = int(state.get("turn_count", 0))
    if turn_count < COMPRESS_AFTER_TURNS:
        return False
    if int(state.get("last_compressed_turn", -1)) == turn_count:
        return False

    messages = list(state.get("messages", []))
    if _effective_prompt_size(state) >= COMPRESS_AFTER_CHARS:
        return True
    return _approx_chars(messages) >= COMPRESS_AFTER_CHARS


def _evidence_draft_identity_key(
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


def _evidence_draft_document_text(draft: dict[str, object]) -> str | None:
    for key in ("sanitized_text", "quote"):
        value = draft.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()[:RERANK_DOCUMENT_CHAR_LIMIT]
    return None


def _evidence_draft_dimension(
    draft: dict[str, object],
    *,
    allowed: list[FocusDimension],
) -> str | None:
    dimension, _ = normalize_dimension_or_none(draft.get("dimension"), allowed=allowed)
    return dimension


async def _rerank_evidence_drafts(
    *,
    evidence_drafts: list[dict[str, object]],
    query: str,
    focus_dimensions: list[FocusDimension],
) -> list[dict[str, object]]:
    copied_drafts: list[dict[str, object]] = []
    eligible: list[tuple[int, str]] = []
    routing_priority_by_index: dict[int, int] = {}
    for index, draft in enumerate(evidence_drafts):
        copied = dict(draft)
        metadata_raw = copied.get("metadata", {})
        copied["metadata"] = dict(metadata_raw) if isinstance(metadata_raw, dict) else {}
        dimension = _evidence_draft_dimension(copied, allowed=focus_dimensions)
        source_type = _normalize_source_type(copied.get("source_type"))
        routing_priority = _routing_priority_for_source(
            dimension=dimension,
            source_type=source_type,
        )
        routing_priority_by_index[index] = routing_priority
        metadata = copied["metadata"]
        if isinstance(metadata, dict):
            metadata["source_routing_priority"] = routing_priority
        copied_drafts.append(copied)
        document = _evidence_draft_document_text(copied)
        if document is not None:
            eligible.append((index, document))

    query_text = query.strip()
    if not copied_drafts or not eligible or not query_text:
        return copied_drafts

    scores_by_index: dict[int, float] = {}
    for batch_start in range(0, len(eligible), RERANK_DOCUMENT_BATCH_SIZE):
        batch = eligible[batch_start : batch_start + RERANK_DOCUMENT_BATCH_SIZE]
        batch_documents = [document for _, document in batch]
        ranked = await rerank_bocha(
            query=query_text,
            documents=batch_documents,
            top_n=len(batch_documents),
        )
        for relative_index, score in ranked:
            if score is None or relative_index < 0 or relative_index >= len(batch):
                continue
            original_index = batch[relative_index][0]
            scores_by_index[original_index] = score

    if not scores_by_index:
        routed_fallback = sorted(
            (
                (
                    routing_priority_by_index.get(index, 0),
                    index,
                    draft,
                )
                for index, draft in enumerate(copied_drafts)
            ),
            key=lambda item: (-item[0], item[1]),
        )
        degraded_sorted: list[dict[str, object]] = []
        for _priority, _index, draft in routed_fallback:
            metadata = draft.get("metadata")
            if isinstance(metadata, dict):
                metadata["rerank_degraded"] = True
                metadata["rerank_degraded_reason"] = "unscored_fallback"
            degraded_sorted.append(draft)
        log.info(
            "researcher.rerank_skipped",
            evidence_draft_count=len(copied_drafts),
            eligible_count=len(eligible),
            degraded=True,
            fallback_order="source_routing",
        )
        return degraded_sorted

    kept_scored: list[tuple[float, int, int, dict[str, object]]] = []
    kept_unscored: list[tuple[int, int, dict[str, object]]] = []
    dropped_count = 0
    for index, draft in enumerate(copied_drafts):
        score = scores_by_index.get(index)
        routing_priority = routing_priority_by_index.get(index, 0)
        metadata = draft["metadata"]
        if score is None:
            if isinstance(metadata, dict):
                metadata["rerank_degraded"] = True
                metadata["rerank_degraded_reason"] = "unscored_fallback"
            kept_unscored.append((routing_priority, index, draft))
            continue
        if score < settings.RERANK_DROP_THRESHOLD:
            dropped_count += 1
            continue
        if isinstance(metadata, dict):
            metadata["rerank_score"] = score
        kept_scored.append((score, routing_priority, index, draft))

    kept_scored.sort(key=lambda item: (-item[0], -item[1], item[2]))
    kept_unscored.sort(key=lambda item: (-item[0], item[1]))
    reranked = [draft for _, _, _, draft in kept_scored]
    reranked.extend(draft for _, _, draft in kept_unscored)
    log.info(
        "researcher.rerank",
        evidence_draft_count=len(copied_drafts),
        eligible_count=len(eligible),
        scored_count=len(scores_by_index),
        dropped_count=dropped_count,
        kept_count=len(reranked),
        drop_threshold=settings.RERANK_DROP_THRESHOLD,
        source_routing_rules=len(_load_source_routing_rules()),
    )
    return reranked


def _select_rerank_reflection_dimension(
    *,
    state: ResearcherSubState,
    evidence_drafts: list[dict[str, object]],
) -> str | None:
    focus_dimensions = list(state.get("focus_dimensions", []))
    if not focus_dimensions:
        return None
    if int(state.get("turn_count", 0)) >= int(state.get("max_turns", MAX_REACT_TURNS)):
        return None
    if settings.RERANK_MIN_HIGH_SCORE_PER_DIM <= 0:
        return None

    reflected = {
        item
        for item in state.get("rerank_reflected_dimensions", [])
        if isinstance(item, str)
    }
    high_score_counts = {dimension: 0 for dimension in focus_dimensions}
    saw_rerank_score = False
    for draft in evidence_drafts:
        metadata_raw = draft.get("metadata", {})
        metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
        score_raw = metadata.get("rerank_score")
        if not isinstance(score_raw, (int, float)):
            continue
        saw_rerank_score = True
        dimension = _evidence_draft_dimension(draft, allowed=focus_dimensions)
        if dimension is None:
            continue
        if float(score_raw) > settings.RERANK_COVERAGE_THRESHOLD:
            high_score_counts[dimension] = high_score_counts.get(dimension, 0) + 1

    if not saw_rerank_score:
        return None
    for dimension in focus_dimensions:
        if dimension in reflected:
            continue
        if high_score_counts.get(dimension, 0) < settings.RERANK_MIN_HIGH_SCORE_PER_DIM:
            return dimension
    return None


def _rerank_reflection_search_args(
    *,
    state: ResearcherSubState,
    dimension: str,
) -> dict[str, object]:
    competitor_id = state.get("competitor_id")
    competitor = competitor_id if isinstance(competitor_id, str) else ""
    domain_hint_raw = state.get("domain_hint")
    domain_hint = domain_hint_raw.strip() if isinstance(domain_hint_raw, str) else ""
    response_language = state.get("response_language")
    quality_terms = "官方 定价 文档 客户 案例 评测 对比" if response_language == "zh" else "official docs pricing customer case review comparison"
    base_query = f"{competitor} {dimension} {state.get('research_topic', '')}".strip()
    primary_query = f"{domain_hint} {base_query} {quality_terms}".strip()
    return {
        "query": primary_query,
        "query_variants": _fallback_query_variants(
            state=state,
            dimension=dimension,
            primary_query=primary_query,
            base_query=base_query,
        ),
        "max_results": _state_search_max_results(state),
        "dimension": dimension,
    }


def _build_rerank_query(state: ResearcherSubState) -> str:
    competitor_raw = state.get("competitor_id")
    competitor = competitor_raw.strip() if isinstance(competitor_raw, str) else ""
    research_topic_raw = state.get("research_topic")
    research_topic = research_topic_raw.strip() if isinstance(research_topic_raw, str) else ""
    dimensions = [
        item.strip()
        for item in state.get("focus_dimensions", [])
        if isinstance(item, str) and item.strip()
    ]
    dimension_context = " ".join(dimensions)
    return " ".join(part for part in [competitor, dimension_context, research_topic] if part).strip()


async def llm_decide(state: ResearcherSubState) -> ResearcherSubState:
    step_id = _state_step_id(state)
    max_turns = int(state.get("max_turns", MAX_REACT_TURNS))
    if int(state.get("turn_count", 0)) >= max_turns:
        return {
            **state,
            "pending_action_args": {"summary": "max researcher turns hit, force finalize"},
            "next_action": "finalize",
        }

    if _needs_compress(state):
        return {
            **state,
            "next_action": "compress",
        }

    domain_hint_raw = state.get("domain_hint")
    domain_hint = domain_hint_raw if isinstance(domain_hint_raw, str) and domain_hint_raw.strip() else None
    reference_urls_raw = state.get("reference_urls", [])
    reference_urls = (
        [item for item in reference_urls_raw if isinstance(item, str)]
        if isinstance(reference_urls_raw, list)
        else []
    )
    discovered_urls_raw = state.get("discovered_urls", [])
    discovered_urls = (
        [item for item in discovered_urls_raw if isinstance(item, str)]
        if isinstance(discovered_urls_raw, list)
        else []
    )
    resolved_official_urls_raw = state.get("resolved_official_urls", [])
    resolved_official_urls = (
        [item for item in resolved_official_urls_raw if isinstance(item, str)]
        if isinstance(resolved_official_urls_raw, list)
        else []
    )
    coverage_matrix_raw = state.get("coverage_matrix", {})
    coverage_matrix = coverage_matrix_raw if isinstance(coverage_matrix_raw, dict) else {}
    compressed_summary_raw = state.get("compressed_summary", "")
    compressed_summary = compressed_summary_raw if isinstance(compressed_summary_raw, str) else ""
    observation_briefs = list(state.get("observation_briefs", []))

    user_prompt = build_researcher_user_prompt(
        research_topic=state["research_topic"],
        competitor_id=state["competitor_id"],
        focus_dimensions=list(state.get("focus_dimensions", [])),
        response_language=(
            state.get("response_language")
            if isinstance(state.get("response_language"), str)
            else None
        ),
        pending_dimensions=list(state.get("pending_dimensions", [])),
        queried_dimensions=list(state.get("queried_dimensions", [])),
        turn_count=int(state.get("turn_count", 0)),
        max_turns=max_turns,
        observation_briefs=observation_briefs,
        compressed_summary=compressed_summary,
        domain_hint=domain_hint,
        target_category=state.get("target_category"),
        category_aliases=list(state.get("category_aliases", [])),
        excluded_categories=list(state.get("excluded_categories", [])),
        market_segments=list(state.get("market_segments", [])),
        scope_policy=state.get("scope_policy"),
        reference_urls=reference_urls,
        discovered_urls=discovered_urls,
        resolved_official_urls=resolved_official_urls,
        coverage_matrix=coverage_matrix,
    )
    pending_dimensions = list(state.get("pending_dimensions", []))
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        harness_result = await complete_structured(
            model_slot="research",
            system_prompt=RESEARCHER_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            output_model=ResearcherDecisionOutput,
            parser=ResearcherDecisionOutput.parse_llm_content,
            fallback_system_prompt=RESEARCHER_SYSTEM_PROMPT,
            fallback_user_prompt=build_researcher_fallback_user_prompt(
                competitor_id=state["competitor_id"],
                pending_dimensions=pending_dimensions,
                queried_dimensions=list(state.get("queried_dimensions", [])),
                turn_count=int(state.get("turn_count", 0)),
                max_turns=max_turns,
                response_language=(
                    state.get("response_language")
                    if isinstance(state.get("response_language"), str)
                    else None
                ),
                domain_hint=domain_hint,
            ),
            repair_user_prompt_builder=lambda errors: build_researcher_repair_user_prompt(
                validation_errors=errors,
                competitor_id=state["competitor_id"],
                pending_dimensions=pending_dimensions,
            ),
            log_event="researcher.harness.finish",
        )
    llm_response = harness_result.llm_response

    llm_calls = list(state.get("llm_calls", []))
    llm_calls.append(llm_response.to_dict())

    messages = list(state.get("messages", []))
    messages.append({"role": "user", "content": user_prompt})
    messages.append({"role": "assistant", "content": str(llm_response.content)})

    action_tuple = (
        harness_result.value.to_action_tuple(
            competitor_id=state["competitor_id"],
            focus_dimensions=list(state.get("focus_dimensions", [])),
            pending_dimensions=list(state.get("pending_dimensions", [])),
        )
        if harness_result.value is not None
        else None
    )
    if action_tuple is not None:
        action, action_args = action_tuple
    else:
        action, action_args = _fallback_action(state)
    action_dimension = _effective_action_dimension(
        state=state,
        action_args=action_args,
        action=action,
    )
    if action == "search_web" and action_dimension is not None:
        guarded_fetch_url = _source_first_fetch_guard_url(
            state=state,
            dimension=action_dimension,
        )
        if guarded_fetch_url is not None:
            action = "fetch_url"
            action_args = {
                "url": guarded_fetch_url,
                "competitor_id": state["competitor_id"],
                "dimension": action_dimension,
            }
    coverage_guard_triggered = False
    if action == "finalize" and pending_dimensions and int(state.get("turn_count", 0)) < max_turns:
        guarded_action, guarded_action_args = _fallback_action(state)
        if guarded_action in TOOL_ACTIONS:
            coverage_guard_triggered = True
            action = guarded_action
            action_args = guarded_action_args
            guarded_dimension = guarded_action_args.get("dimension")
            log_context = bind_step(step_id) if step_id is not None else nullcontext()
            with log_context:
                log.info(
                    "researcher.coverage_guard",
                    competitor_id=state["competitor_id"],
                    action=guarded_action,
                    dimension=guarded_dimension if isinstance(guarded_dimension, str) else None,
                    pending_dimensions=pending_dimensions,
                    turn_count=int(state.get("turn_count", 0)),
                    max_turns=max_turns,
                )
    if action != "fetch_url" and int(state.get("turn_count", 0)) < max_turns:
        deepen_action = _shallow_evidence_deepen_action(state)
        if deepen_action is not None:
            action, action_args = deepen_action
            log_context = bind_step(step_id) if step_id is not None else nullcontext()
            with log_context:
                log.info(
                    "researcher.deepen_shallow_evidence",
                    competitor_id=state["competitor_id"],
                    dimension=action_args.get("dimension"),
                    url=action_args.get("url"),
                    turn_count=int(state.get("turn_count", 0)),
                    max_turns=max_turns,
                )
    if action == "fetch_url":
        target_url_raw = action_args.get("url")
        target_url = target_url_raw.strip() if isinstance(target_url_raw, str) else ""
        if target_url and target_url in _already_fetched_urls(state):
            # Re-fetching a URL already pulled this run only re-admits identical
            # fallback junk. Hand back to the attempt-count-aware fallback, which
            # prefers an un-attempted search/dimension; if it still resolves to the
            # same dead URL, stop instead of looping the turn budget away.
            redirect_action, redirect_args = _fallback_action(state)
            if redirect_action == "fetch_url":
                redirect_url_raw = redirect_args.get("url")
                redirect_url = (
                    redirect_url_raw.strip() if isinstance(redirect_url_raw, str) else ""
                )
                if not redirect_url or redirect_url in _already_fetched_urls(state):
                    redirect_action = "finalize"
                    redirect_args = {"summary": "stop re-fetching already-fetched urls"}
            action, action_args = redirect_action, redirect_args
            log_context = bind_step(step_id) if step_id is not None else nullcontext()
            with log_context:
                log.info(
                    "researcher.skip_duplicate_fetch",
                    competitor_id=state["competitor_id"],
                    duplicate_url=target_url,
                    redirect_action=action,
                    turn_count=int(state.get("turn_count", 0)),
                    max_turns=max_turns,
                )
    pending_action_args = {"_action": action, **action_args}
    next_action: Literal["tool_exec", "compress", "finalize"]
    if action in TOOL_ACTIONS:
        next_action = "tool_exec"
    else:
        next_action = "finalize"

    return {
        **state,
        "llm_calls": llm_calls,
        "messages": messages,
        "pending_action_args": pending_action_args,
        "next_action": next_action,
    }


def _append_evidence_drafts(
    *,
    evidence_drafts: list[dict[str, object]],
    observation: dict[str, object],
    focus_dimensions: list[FocusDimension],
) -> list[dict[str, object]]:
    observation_metadata_raw = observation.get("metadata", {})
    observation_metadata = (
        observation_metadata_raw if isinstance(observation_metadata_raw, dict) else {}
    )
    snippets_raw = observation.get("snippets", [])
    snippets = snippets_raw if isinstance(snippets_raw, list) else []
    dimension_raw = observation.get("dimension") or observation_metadata.get("dimension")
    competitor_id_raw = observation.get("competitor_id") or observation_metadata.get("competitor_id")
    competitor_id = competitor_id_raw if isinstance(competitor_id_raw, str) else "unknown"
    dimension, dimension_drop_reason = normalize_dimension_or_none(
        dimension_raw,
        allowed=focus_dimensions,
    )
    seen_evidence = set()
    for item in evidence_drafts:
        if not isinstance(item, dict):
            continue
        item_competitor = item.get("competitor_id")
        item_quote = item.get("quote")
        if not isinstance(item_competitor, str) or not isinstance(item_quote, str):
            continue
        item_dimension, _ = normalize_dimension_or_none(
            item.get("dimension"),
            allowed=focus_dimensions,
        )
        item_source_url = item.get("source_url")
        seen_evidence.add(
            _evidence_draft_identity_key(
                competitor_id=item_competitor,
                dimension=item_dimension,
                source_url=item_source_url if isinstance(item_source_url, str) else None,
                quote=item_quote,
            )
        )

    for snippet in snippets:
        if not isinstance(snippet, dict):
            continue
        quote = snippet.get("quote") or snippet.get("sanitized_text")
        sanitized_text = snippet.get("sanitized_text")
        source_url = snippet.get("source_url")
        source_title = snippet.get("source_title")
        source_type = snippet.get("source_type")
        desensitized = snippet.get("desensitized")
        metadata = snippet.get("metadata", {})
        if not isinstance(quote, str):
            continue
        if source_url is not None and not isinstance(source_url, str):
            continue
        if source_title is not None and not isinstance(source_title, str):
            continue
        if not isinstance(source_type, str):
            source_type = "article"
        else:
            try:
                source_type = validate_source_type(source_type)
            except ValueError:
                source_type = "article"
        if not isinstance(sanitized_text, str):
            sanitized_text = quote
        if not isinstance(metadata, dict):
            metadata = {}
        snippet_dimension_raw = metadata.get("dimension")
        snippet_competitor_raw = metadata.get("competitor_id")
        snippet_dimension, snippet_dimension_drop_reason = normalize_dimension_or_none(
            snippet_dimension_raw if isinstance(snippet_dimension_raw, str) else dimension_raw,
            allowed=focus_dimensions,
        )
        snippet_competitor = (
            snippet_competitor_raw
            if isinstance(snippet_competitor_raw, str)
            else competitor_id
        )
        evidence_key = _evidence_draft_identity_key(
            competitor_id=snippet_competitor,
            dimension=snippet_dimension,
            source_url=source_url,
            quote=quote,
        )
        if evidence_key in seen_evidence:
            continue
        seen_evidence.add(evidence_key)
        metadata = {
            **metadata,
            "dimension_drop_reason": snippet_dimension_drop_reason or dimension_drop_reason,
        }
        evidence_drafts.append(
            {
                "dimension": snippet_dimension,
                "competitor_id": snippet_competitor,
                "quote": quote,
                "source_url": source_url,
                "source_title": source_title,
                "source_type": source_type,
                "sanitized_text": sanitized_text,
                "desensitized": bool(desensitized),
                "metadata": metadata,
            }
        )
    return evidence_drafts


async def tool_exec(state: ResearcherSubState) -> ResearcherSubState:
    action_args = dict(state.get("pending_action_args", {}))
    action_raw = action_args.pop("_action", None)
    if not isinstance(action_raw, str):
        return {
            **state,
            "pending_action_args": {},
            "next_action": "finalize",
        }
    channel_action = ACTION_TO_CHANNEL.get(action_raw)
    if channel_action is None:
        return {
            **state,
            "pending_action_args": {},
            "next_action": "finalize",
        }
    registry = get_channel_registry()
    dimension = (
        _effective_action_dimension(
            state=state, action_args=action_args, action=action_raw
        )
        if action_raw in DIMENSIONAL_TOOL_ACTIONS
        else None
    )
    if dimension is not None:
        action_args["dimension"] = dimension
    if action_raw == "search_web":
        response_language = state.get("response_language")
        market_scope = state.get("market_scope")
        competitor_id_raw = state.get("competitor_id")
        if isinstance(response_language, str) and response_language in {"zh", "en"}:
            action_args.setdefault("response_language", response_language)
        if isinstance(market_scope, str) and market_scope.strip():
            action_args.setdefault("market_scope", market_scope.strip())
        if isinstance(competitor_id_raw, str) and competitor_id_raw.strip():
            action_args.setdefault("competitor_id", competitor_id_raw.strip())
        official_hosts = sorted(_state_official_hosts(state))
        if official_hosts:
            action_args.setdefault("official_hosts", official_hosts)
        # LLM-issued searches arrive without query_variants; widen recall with the
        # same locale + dimension-synonym variants the fallback dispatcher uses, so
        # same-language pages that literally name the vendor surface (absorbs most
        # cross-language alias misses without a dedicated alias pipeline).
        query_raw = action_args.get("query")
        dimension_for_variants = action_args.get("dimension")
        if (
            not action_args.get("query_variants")
            and isinstance(query_raw, str)
            and query_raw.strip()
            and isinstance(dimension_for_variants, str)
            and dimension_for_variants.strip()
        ):
            action_args["query_variants"] = _fallback_query_variants(
                state=state,
                dimension=dimension_for_variants,
                primary_query=query_raw.strip(),
                base_query=query_raw.strip(),
            )
    if action_raw == "fetch_url":
        competitor_id_raw = state.get("competitor_id")
        if isinstance(competitor_id_raw, str) and competitor_id_raw.strip():
            action_args.setdefault("competitor_id", competitor_id_raw.strip())
        query_raw = action_args.get("query")
        if not isinstance(query_raw, str) or not query_raw.strip():
            research_topic_raw = state.get("research_topic")
            if isinstance(research_topic_raw, str) and research_topic_raw.strip():
                action_args["query"] = research_topic_raw.strip()

    run_id_raw = state.get("run_id")
    run_id = run_id_raw if isinstance(run_id_raw, str) else None
    step_id = _state_step_id(state)
    competitor_id_raw = state.get("competitor_id")
    competitor_id = competitor_id_raw if isinstance(competitor_id_raw, str) else None
    turn_index = int(state.get("turn_count", 0)) + 1
    args_summary = _safe_tool_args_summary(action_args)

    if run_id is not None:
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.TOOL_START,
            step_id=step_id,
            payload={
                "tool": action_raw,
                "competitor_id": competitor_id,
                "dimension": dimension,
                "turn": turn_index,
                "args_summary": args_summary,
            },
        )

    tool_started_at = time.monotonic()
    tool_exc: Exception | None = None
    try:
        log_context = bind_step(step_id) if step_id is not None else nullcontext()
        with log_context:
            observation = await registry.invoke(channel_action, args=action_args)
        observed_args = {**action_args, **observation.args}
        if dimension is not None:
            observed_args["dimension"] = dimension
        observation_row = {
            "tool": action_raw,
            "args": observed_args,
            "result": observation.result.model_dump(),
        }
    except (
        ChannelError,
        ChannelNotRegisteredError,
        DesensitizeError,
        ValueError,
        TypeError,
        RuntimeError,
    ) as exc:
        tool_exc = exc
        observation_row = {
            "tool": action_raw,
            "args": action_args,
            "error": str(exc),
        }
    latency_ms = int((time.monotonic() - tool_started_at) * 1000)
    log_fields = _tool_observation_log_fields(observation_row=observation_row, exc=tool_exc)
    result_diagnostics = _tool_result_diagnostics(observation_row)

    if run_id is not None:
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.TOOL_FINISH,
            step_id=step_id,
            payload={
                "tool": action_raw,
                "competitor_id": competitor_id,
                "dimension": dimension,
                "turn": turn_index,
                "success": log_fields["success"],
                "snippet_count": result_diagnostics["snippet_count"],
                "snippet_preview": result_diagnostics["snippet_preview"],
                "source_type_distribution": result_diagnostics["source_type_distribution"],
                "latency_ms": latency_ms,
                "error_class": log_fields["error_class"],
                "error_preview": log_fields["error_preview"],
                "error": log_fields["error_preview"],
            },
        )

    observations_log = list(state.get("observations_log", []))
    observations_log.append(observation_row)

    observation_briefs = list(state.get("observation_briefs", []))
    observation_briefs.append(
        _build_observation_brief(
            tool=action_raw,
            args=action_args,
            observation_row=observation_row,
            dimension=dimension,
        )
    )

    discovered_urls = list(state.get("discovered_urls", []))
    if action_raw == "search_web" and "error" not in observation_row:
        discovered_urls = _merge_discovered_urls(
            discovered_urls,
            _extract_urls_from_observation(observation_row),
        )
    search_call_count = int(state.get("search_call_count", 0))
    if action_raw == "search_web":
        search_call_count += 1
    official_fetch_count = int(state.get("official_fetch_count", 0))
    if action_raw == "fetch_url":
        url_raw = action_args.get("url")
        if (
            isinstance(url_raw, str)
            and url_raw.strip()
            and _url_host_matches(url_raw.strip(), _state_official_hosts(state))
        ):
            official_fetch_count += 1

    result_payload_raw = observation_row.get("result", {}) if isinstance(observation_row, dict) else {}
    if isinstance(result_payload_raw, dict):
        result_payload = {
            **result_payload_raw,
            "metadata": {
                **(
                    result_payload_raw.get("metadata", {})
                    if isinstance(result_payload_raw.get("metadata"), dict)
                    else {}
                ),
                "dimension": dimension,
                "competitor_id": state["competitor_id"],
            },
        }
    else:
        result_payload = {}

    evidence_drafts_before = list(state.get("evidence_drafts", []))
    draft_count_before = len(evidence_drafts_before)
    evidence_drafts = _append_evidence_drafts(
        evidence_drafts=evidence_drafts_before,
        observation=result_payload,
        focus_dimensions=list(state.get("focus_dimensions", [])),
    )
    draft_count_after = len(evidence_drafts)
    draft_delta = max(draft_count_after - draft_count_before, 0)

    focus_dimensions = list(state.get("focus_dimensions", []))
    coverage_matrix = _build_coverage_matrix(
        state=state,
        evidence_drafts=evidence_drafts,
    )
    state_with_latest_observation: ResearcherSubState = {
        **state,
        "observations_log": observations_log,
    }
    pending_dimensions = _pending_dimensions_from_coverage(
        focus_dimensions=focus_dimensions,
        coverage_matrix=coverage_matrix,
        state=state_with_latest_observation,
    )
    queried_dimensions = list(state.get("queried_dimensions", []))
    if dimension is not None and dimension not in queried_dimensions:
        queried_dimensions.append(dimension)

    messages = list(state.get("messages", []))
    messages.append({"role": "tool", "content": str(observation_row)})
    next_turn_count = int(state.get("turn_count", 0)) + 1
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        log.info(
            "researcher.funnel.search_to_draft",
            tool=action_raw,
            competitor_id=state.get("competitor_id"),
            dimension=dimension,
            search_results=(
                result_diagnostics["snippet_count"]
                if action_raw == "search_web"
                else 0
            ),
            draft_count_before=draft_count_before,
            draft_count_after=draft_count_after,
            draft_delta=draft_delta,
        )
        log.info(
            "researcher.tool_call",
            tool=action_raw,
            dimension=dimension,
            competitor_id=state.get("competitor_id"),
            turn_count=next_turn_count,
            success=log_fields["success"],
            snippet_count=result_diagnostics["snippet_count"],
            snippet_preview=result_diagnostics["snippet_preview"],
            source_type_distribution=result_diagnostics["source_type_distribution"],
            latency_ms=latency_ms,
            error_class=log_fields["error_class"],
            error_preview=log_fields["error_preview"],
        )

    return {
        **state,
        "turn_count": next_turn_count,
        "observations_log": observations_log,
        "observation_briefs": observation_briefs,
        "discovered_urls": discovered_urls,
        "search_call_count": search_call_count,
        "official_fetch_count": official_fetch_count,
        "coverage_matrix": coverage_matrix,
        "evidence_drafts": evidence_drafts,
        "pending_dimensions": pending_dimensions,
        "queried_dimensions": queried_dimensions,
        "messages": messages,
        "pending_action_args": {},
    }


async def compress(state: ResearcherSubState) -> ResearcherSubState:
    step_id = _state_step_id(state)
    compressed_summary_raw = state.get("compressed_summary", "")
    prior_summary = compressed_summary_raw if isinstance(compressed_summary_raw, str) else ""
    user_prompt = build_compression_user_prompt(
        messages=list(state.get("messages", [])),
        observation_briefs=list(state.get("observation_briefs", [])),
        evidence_drafts=list(state.get("evidence_drafts", [])),
        compressed_summary=prior_summary,
    )
    observations_log = list(state.get("observations_log", []))
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        harness_result = await complete_structured(
            model_slot="compression",
            system_prompt=RESEARCHER_COMPRESSION_PROMPT,
            user_prompt=user_prompt,
            output_model=ResearcherCompressionOutput,
            parser=ResearcherCompressionOutput.parse_llm_content,
            fallback_system_prompt=RESEARCHER_COMPRESSION_PROMPT,
            fallback_user_prompt=build_compression_fallback_user_prompt(
                observations_log=observations_log,
                evidence_drafts=list(state.get("evidence_drafts", [])),
            ),
            repair_user_prompt_builder=lambda errors: build_compression_repair_user_prompt(
                validation_errors=errors,
                observation_count=len(observations_log),
            ),
            log_event="researcher.compress.harness.finish",
        )
    llm_response = harness_result.llm_response

    llm_calls = list(state.get("llm_calls", []))
    llm_calls.append(llm_response.to_dict())

    if harness_result.value is not None:
        summary = harness_result.value.compressed_summary
    else:
        summary = f"compressed with {len(observations_log)} observations"
    next_compression_count = int(state.get("compression_count", 0)) + 1
    pruned_observations = _archive_observations_log(list(state.get("observations_log", [])))
    pruned_briefs = list(state.get("observation_briefs", []))[-12:]
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        log.info(
            "researcher.compress",
            compression_count=next_compression_count,
            observations_count=len(state.get("observations_log", [])),
            summary_len=len(summary),
        )

    return {
        **state,
        "compression_count": next_compression_count,
        "last_compressed_turn": int(state.get("turn_count", 0)),
        "llm_calls": llm_calls,
        "observations_log": pruned_observations,
        "observation_briefs": pruned_briefs,
        "compressed_summary": summary,
        "messages": [
            {"role": "system", "content": "compressed researcher context"},
            {"role": "assistant", "content": summary},
        ],
        "final_summary": summary,
    }


async def finalize(state: ResearcherSubState) -> ResearcherSubState:
    step_id = _state_step_id(state)
    if state.get("final_summary"):
        final_summary = state.get("final_summary")
        coverage_matrix = _build_coverage_matrix(
            state=state,
            evidence_drafts=list(state.get("evidence_drafts", [])),
        )
        log_context = bind_step(step_id) if step_id is not None else nullcontext()
        with log_context:
            log.info(
                "researcher.finalize",
                evidence_draft_count=len(state.get("evidence_drafts", [])),
                final_summary_len=len(final_summary) if isinstance(final_summary, str) else 0,
            )
        return {
            **state,
            "coverage_matrix": coverage_matrix,
            "pending_action_args": {},
            "next_action": "finalize",
        }

    evidence_drafts = await _rerank_evidence_drafts(
        evidence_drafts=list(state.get("evidence_drafts", [])),
        query=_build_rerank_query(state),
        focus_dimensions=list(state.get("focus_dimensions", [])),
    )
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        log.info(
            "researcher.funnel.post_rerank",
            competitor_id=state.get("competitor_id"),
            post_rerank_count=len(evidence_drafts),
        )
    coverage_matrix = _build_coverage_matrix(
        state=state,
        evidence_drafts=evidence_drafts,
    )
    reflection_dimension = _select_rerank_reflection_dimension(
        state=state,
        evidence_drafts=evidence_drafts,
    )
    if reflection_dimension is not None:
        reflected_dimensions = [
            item
            for item in state.get("rerank_reflected_dimensions", [])
            if isinstance(item, str)
        ]
        reflected_dimensions.append(reflection_dimension)
        pending_dimensions = [
            reflection_dimension,
            *[
                item
                for item in state.get("pending_dimensions", [])
                if item != reflection_dimension
            ],
        ]
        action_args = _rerank_reflection_search_args(
            state=state,
            dimension=reflection_dimension,
        )
        log_context = bind_step(step_id) if step_id is not None else nullcontext()
        with log_context:
            log.info(
                "researcher.rerank_reflect_reresearch",
                competitor_id=state.get("competitor_id"),
                dimension=reflection_dimension,
                turn_count=int(state.get("turn_count", 0)),
                max_turns=int(state.get("max_turns", MAX_REACT_TURNS)),
                coverage_threshold=settings.RERANK_COVERAGE_THRESHOLD,
                min_high_score_per_dim=settings.RERANK_MIN_HIGH_SCORE_PER_DIM,
            )
        return {
            **state,
            "evidence_drafts": evidence_drafts,
            "coverage_matrix": coverage_matrix,
            "pending_dimensions": pending_dimensions,
            "rerank_reflected_dimensions": reflected_dimensions,
            "pending_action_args": {"_action": "search_web", **action_args},
            "next_action": "tool_exec",
        }

    observations = list(state.get("observations_log", []))
    final_summary = f"finalized with {len(observations)} observations"
    log_context = bind_step(step_id) if step_id is not None else nullcontext()
    with log_context:
        log.info(
            "researcher.finalize",
            evidence_draft_count=len(evidence_drafts),
            final_summary_len=len(final_summary),
        )
    return {
        **state,
        "evidence_drafts": evidence_drafts,
        "coverage_matrix": coverage_matrix,
        "pending_action_args": {},
        "next_action": "finalize",
        "final_summary": final_summary,
    }


def _route_after_llm_decide(
    state: ResearcherSubState,
) -> Literal["tool_exec", "compress", "finalize"]:
    next_action = state.get("next_action", "finalize")
    if next_action in {"tool_exec", "compress", "finalize"}:
        return next_action
    return "finalize"


def _route_after_finalize(state: ResearcherSubState) -> Literal["tool_exec", "end"]:
    action_raw = dict(state.get("pending_action_args", {})).get("_action")
    if state.get("next_action") == "tool_exec" and action_raw in TOOL_ACTIONS:
        return "tool_exec"
    return "end"


def build_researcher_subgraph():
    graph = StateGraph(ResearcherSubState)
    graph.add_node("llm_decide", llm_decide)
    graph.add_node("tool_exec", tool_exec)
    graph.add_node("compress", compress)
    graph.add_node("finalize", finalize)
    graph.set_entry_point("llm_decide")
    graph.add_conditional_edges(
        "llm_decide",
        _route_after_llm_decide,
        {
            "tool_exec": "tool_exec",
            "compress": "compress",
            "finalize": "finalize",
        },
    )
    graph.add_edge("tool_exec", "llm_decide")
    graph.add_edge("compress", "llm_decide")
    graph.add_conditional_edges(
        "finalize",
        _route_after_finalize,
        {
            "tool_exec": "tool_exec",
            "end": END,
        },
    )
    return graph.compile()


@lru_cache
def get_researcher_subgraph():
    return build_researcher_subgraph()

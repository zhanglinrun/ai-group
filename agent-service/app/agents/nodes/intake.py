from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from langgraph.types import interrupt
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.state import AgentState, spread_without_accumulators
from agents.state_coercion import coerce_intake_draft_or_default, coerce_intake_history
from db.engine import get_session_factory
from models.run import Run
from models.step import Step
from schemas.agent_outputs import IntakeTurnOutput
from schemas.contracts import normalize_dimensions
from schemas.ids import make_id
from schemas.intake import (
    IntakeClarifyRequest,
    IntakeExchange,
    IntakeUserReply,
    RunIntakeDraft,
    infer_scope_policy,
    normalize_optional_text,
    stable_unique_text,
    text_mentions_any_term,
)
from service.event_bus import RunEventType, emit_run_event
from service.locale import detect_language
from service.llm import (
    INTAKE_SYSTEM_PROMPT,
    build_intake_fallback_user_prompt,
    build_intake_repair_user_prompt,
    build_intake_user_prompt,
)
from service.llm.harness import complete_structured
from service.llm.records import build_llm_call_record
from service.llm.response import LLMResponse
from utils.log_node import log_node
from utils.logger import bind_step, get_logger

log = get_logger("agents.intake")

_USER_ROLES: frozenset[str] = frozenset({"pm", "founder", "sales", "investor"})

# Keyword-based normalization tables for the wait node's deterministic merge.
# Why: user-facing chips show bilingual labels (e.g. "PM / 产品经理"), and the
# LLM may also emit free-form Chinese options. The wait node MUST translate any
# of these back to internal enum values so user_role / discovery_mode reliably
# land in `intake_draft` without depending on the next LLM turn's parsing.
_ROLE_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("pm", ("pm", "product manager", "产品经理", "产品负责人")),
    ("founder", ("founder", "co-founder", "创始人", "创业者", "ceo")),
    ("sales", ("sales", "销售", "bd", "客户成功")),
    ("investor", ("investor", "vc", "投资人", "投资经理", "分析师")),
)
_DISCOVERY_ON_KEYWORDS: tuple[str, ...] = (
    "auto-discover",
    "discover",
    "帮我发现",
    "agent 帮",
    "agent帮",
    "由 agent",
    "由agent",
    "自动发现",
)
_DISCOVERY_OFF_KEYWORDS: tuple[str, ...] = (
    "我已有名单",
    "已有名单",
    "explicit",
    "已知",
    "我自己来",
)
# report_depth is intentionally NOT an intake-inferred field: the analysis tier
# is an authoritative run-level setting chosen up front in the composer (like
# ChatGPT/Gemini Deep Research). The clarification conversation is tier-agnostic,
# so intake never asks about it nor overwrites the user's selection.
_OPTIONAL_CLARIFY_TARGETS: frozenset[str] = frozenset(
    {
        "domain_hint",
        "target_category",
        "category_aliases",
        "excluded_categories",
        "market_segments",
        "focus_dimensions",
        "reference_urls",
        "self_product",
        "market_scope",
        "time_context",
    }
)
_OPTIONAL_FREE_TEXT_TARGETS: frozenset[str] = frozenset(
    {"domain_hint", "target_category", "self_product", "market_scope", "time_context"}
)
_MAX_OPTIONAL_CLARIFY_TURNS_AFTER_COMPLETE = 2
_AMBIGUOUS_TERMS: frozenset[str] = frozenset({"opc"})
_ONE_PERSON_COMPANY_MARKERS: tuple[str, ...] = (
    "one person company",
    "one-person company",
    "one person",
    "一人公司",
    "一个人公司",
    "个人公司",
    "单人公司",
)
_OPEN_PLATFORM_COMMUNICATION_MARKERS: tuple[str, ...] = (
    "open platform communications",
    "opc ua",
    "工业通信",
    "工业协议",
)


def _ensure_response_language(draft: RunIntakeDraft, user_query: str) -> RunIntakeDraft:
    if draft.response_language in {"zh", "en"}:
        return draft
    return draft.model_copy(update={"response_language": detect_language(user_query)})


def _match_keyword(text: str, table: tuple[tuple[str, tuple[str, ...]], ...]) -> str | None:
    needle = text.casefold().strip()
    if not needle:
        return None
    for value, keywords in table:
        if any(keyword.casefold() in needle for keyword in keywords):
            return value
    return None


def _split_competitor_list(text: str) -> list[str]:
    """Best-effort parse: user might list competitors in free text, comma/顿号/换行 separated."""
    if not text or not text.strip():
        return []
    # Replace common CJK separators with comma, then split.
    normalized = text.replace("、", ",").replace("，", ",").replace(";", ",").replace("\n", ",")
    parts = [piece.strip() for piece in normalized.split(",")]
    # Strip surrounding quotes / bullets that users often paste.
    cleaned = [piece.strip(" \"'·-•*") for piece in parts if piece.strip(" \"'·-•*")]
    # De-dup preserving order.
    seen: set[str] = set()
    out: list[str] = []
    for item in cleaned:
        key = item.casefold()
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def _resolve_session_factory(state: AgentState) -> async_sessionmaker[AsyncSession]:
    return get_session_factory()


def _history_to_prompt(history: list[IntakeExchange]) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for exchange in history:
        rows.append(
            {
                "question": exchange.clarify.question,
                "field_targets": list(exchange.clarify.field_targets),
                "reply_text": exchange.reply.text,
                "reply_options": list(exchange.reply.selected_options),
            }
        )
    return rows


# Soft upper bound matches the runs.title column (varchar 120) but we trim to
# ~24 chars for display — the DB cap is just defense-in-depth for unicode.
_TITLE_DISPLAY_MAX = 24
_TITLE_DB_MAX = 120


def _extract_summary_title(
    content: dict[str, object],
    draft: RunIntakeDraft,
    user_query: str,
) -> str | None:
    """Return a stable short title for the run.

    Source priority:
      1. LLM-provided summary_title (the intake LLM is instructed to emit one
         at action=complete; same call, zero extra cost).
      2. Fallback: first 24 chars of analysis_intent (always populated when
         action=complete) — keeps a readable label even if the LLM omits the
         field on flaky days.
      3. Last resort: first non-empty line of user_query, trimmed.
    The DB column accepts None; FE falls back to truncating user_query.
    """
    raw_title = content.get("summary_title")
    if isinstance(raw_title, str):
        cleaned = raw_title.strip().strip("\"'")
        if cleaned:
            return cleaned[:_TITLE_DB_MAX]
    intent = draft.analysis_intent
    if isinstance(intent, str) and intent.strip():
        return intent.strip()[:_TITLE_DISPLAY_MAX]
    if user_query:
        first_line = next(
            (line.strip() for line in user_query.splitlines() if line.strip()),
            "",
        )
        if first_line:
            return first_line[:_TITLE_DISPLAY_MAX]
    return None


async def _persist_run_title(*, run_id: str, title: str) -> None:
    """Mirror the intake-derived title onto the Run row.

    Same rationale as `_persist_intake_draft_to_run` — it lets GET /api/runs
    render a short label without poking graph state. Skips the write if the
    row no longer exists (race with a cancel/delete is benign).
    """
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.title = title[:_TITLE_DB_MAX]
        await session.commit()


async def _persist_intake_draft_to_run(*, run_id: str, draft: RunIntakeDraft) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.intake_draft = draft.model_dump(exclude={"is_complete"})
        run.domain_hint = draft.domain_hint
        await session.commit()


def _apply_patch(draft: RunIntakeDraft, patch: dict[str, object]) -> RunIntakeDraft:
    if not patch:
        return draft
    base = draft.model_dump(exclude={"is_complete"})
    role_raw = patch.get("user_role")
    if isinstance(role_raw, str) and role_raw in _USER_ROLES:
        base["user_role"] = role_raw
    intent_raw = patch.get("analysis_intent")
    if isinstance(intent_raw, str) and intent_raw.strip():
        base["analysis_intent"] = intent_raw.strip()
    explicit_raw = patch.get("competitors_explicit")
    if isinstance(explicit_raw, list):
        normalized = [str(c).strip() for c in explicit_raw if isinstance(c, str) and c.strip()]
        if normalized:
            base["competitors_explicit"] = normalized
    discovery_raw = patch.get("competitors_discovery_mode")
    if isinstance(discovery_raw, bool):
        base["competitors_discovery_mode"] = discovery_raw
    domain_raw = normalize_optional_text(patch.get("domain_hint"))
    if domain_raw is not None:
        canonical_domain = _canonical_domain_hint(domain_raw)
        base["domain_hint"] = canonical_domain
        rewritten_intent = _rewrite_ambiguous_intent(
            current_intent=base.get("analysis_intent"),
            canonical_domain=canonical_domain,
        )
        if rewritten_intent is not None:
            base["analysis_intent"] = rewritten_intent
    focus_raw = patch.get("focus_dimensions")
    if isinstance(focus_raw, list):
        normalized = normalize_dimensions(
            stable_unique_text([d for d in focus_raw if isinstance(d, str)]),
            allow_empty=True,
        )
        if normalized:
            base["focus_dimensions"] = normalized
    for list_field in ("category_aliases", "excluded_categories", "market_segments"):
        list_raw = patch.get(list_field)
        if isinstance(list_raw, list):
            normalized = stable_unique_text([item for item in list_raw if isinstance(item, str)])
            if normalized:
                base[list_field] = normalized
    urls_raw = patch.get("reference_urls")
    if isinstance(urls_raw, list):
        normalized = stable_unique_text([u for u in urls_raw if isinstance(u, str)])
        if normalized:
            base["reference_urls"] = normalized
    for free_text_field in ("self_product", "market_scope", "time_context"):
        if free_text_field in patch:
            base[free_text_field] = normalize_optional_text(patch.get(free_text_field))
    target_category = normalize_optional_text(patch.get("target_category"))
    if target_category is not None:
        current_target = normalize_optional_text(base.get("target_category"))
        target_would_narrow_broad_scope = (
            current_target is not None
            and infer_scope_policy(current_target) == "broad_market"
            and infer_scope_policy(target_category) != "broad_market"
            and not text_mentions_any_term(draft.user_query, [target_category])
        )
        if target_would_narrow_broad_scope:
            base["market_segments"] = stable_unique_text(
                [*base.get("market_segments", []), target_category]
            )
        else:
            base["target_category"] = target_category
    scope_policy_raw = patch.get("scope_policy")
    if scope_policy_raw in {"explicit_category", "broad_market"}:
        base["scope_policy"] = scope_policy_raw
    language_raw = patch.get("response_language")
    if isinstance(language_raw, str) and language_raw in {"zh", "en"}:
        base["response_language"] = language_raw
    archetype_raw = patch.get("analysis_archetype")
    if isinstance(archetype_raw, str) and archetype_raw in {"comparison", "landscape"}:
        base["analysis_archetype"] = archetype_raw
    return RunIntakeDraft.model_validate(base)


def _clarify_target_satisfied(field_target: str, draft: RunIntakeDraft) -> bool:
    """Whether a clarify `field_target` is already satisfied by the current draft.

    Only the completion-gate fields are tracked; unknown targets are treated as
    unsatisfied so a genuinely new question (e.g. an optional dimension the LLM
    wants to confirm) is never silently dropped.
    """
    if field_target == "user_role":
        return draft.user_role is not None
    if field_target == "analysis_intent":
        return bool(draft.analysis_intent and draft.analysis_intent.strip())
    if field_target in {"competitors_explicit", "competitors_discovery_mode"}:
        return bool(draft.competitors_explicit) or draft.competitors_discovery_mode is True
    if field_target == "domain_hint":
        return bool(draft.domain_hint and draft.domain_hint.strip())
    if field_target == "target_category":
        return bool(draft.target_category and draft.target_category.strip())
    if field_target in {"category_aliases", "excluded_categories", "market_segments"}:
        return bool(getattr(draft, field_target))
    if field_target == "focus_dimensions":
        return bool(draft.focus_dimensions)
    if field_target == "reference_urls":
        return bool(draft.reference_urls)
    if field_target in {"self_product", "market_scope", "time_context"}:
        value = getattr(draft, field_target)
        return bool(isinstance(value, str) and value.strip())
    return False


def _unsatisfied_clarify_targets(
    clarify: IntakeClarifyRequest, draft: RunIntakeDraft
) -> list[str]:
    """Subset of clarify field_targets the draft does not yet satisfy."""
    return [
        target
        for target in clarify.field_targets
        if not _clarify_target_satisfied(target, draft)
    ]


def _answered_optional_turn_count(history: list[IntakeExchange]) -> int:
    return sum(
        1
        for exchange in history
        if any(target in _OPTIONAL_CLARIFY_TARGETS for target in exchange.clarify.field_targets)
    )


def _contains_ambiguous_term(value: str | None) -> bool:
    if not isinstance(value, str):
        return False
    lowered = value.casefold()
    return any(term in lowered for term in _AMBIGUOUS_TERMS)


def _canonical_domain_hint(value: str) -> str:
    lowered = value.casefold()
    if any(marker in lowered for marker in _ONE_PERSON_COMPANY_MARKERS):
        return "one person company monetization"
    if any(marker in lowered for marker in _OPEN_PLATFORM_COMMUNICATION_MARKERS):
        return "open platform communications"
    return value.strip()


def _rewrite_ambiguous_intent(*, current_intent: object, canonical_domain: str) -> str | None:
    if not isinstance(current_intent, str) or not current_intent.strip():
        return None
    if "opc" not in current_intent.casefold():
        return None
    if canonical_domain == "one person company monetization":
        return current_intent.replace("OPC", "一人公司（One Person Company）").replace(
            "opc", "一人公司（One Person Company）"
        )
    if canonical_domain == "open platform communications":
        return current_intent.replace("OPC", "OPC 工业通信协议").replace(
            "opc", "OPC 工业通信协议"
        )
    return None


def _history_resolved_ambiguous_domain(history: list[IntakeExchange]) -> bool:
    for exchange in history:
        targets = set(exchange.clarify.field_targets)
        if targets & {"domain_hint", "analysis_intent"}:
            return True
    return False


def _needs_ambiguous_term_clarify(
    *,
    draft: RunIntakeDraft,
    history: list[IntakeExchange],
) -> bool:
    if _history_resolved_ambiguous_domain(history):
        return False
    return _contains_ambiguous_term(draft.user_query) or _contains_ambiguous_term(draft.analysis_intent)


def _ambiguous_term_clarify(draft: RunIntakeDraft) -> IntakeClarifyRequest:
    return IntakeClarifyRequest(
        question=(
            "这里的 OPC 可能有多种含义。您指的是一人公司/个人可落地变现项目，"
            "还是工业通信协议 OPC UA，或其他含义？"
        ),
        field_targets=["domain_hint", "analysis_intent"],
        suggested_options=[
            "一人公司 / One Person Company",
            "工业通信协议 / Open Platform Communications",
            "其他含义，我补充说明",
        ],
        suggested_answer="我指一人公司/个人可落地变现项目。",
    )


def _answered_optional_targets(history: list[IntakeExchange]) -> set[str]:
    out: set[str] = set()
    for exchange in history:
        for target in exchange.clarify.field_targets:
            if target in _OPTIONAL_CLARIFY_TARGETS:
                out.add(target)
    return out


def _should_drop_optional_clarify(
    clarify: IntakeClarifyRequest,
    history: list[IntakeExchange],
) -> bool:
    """Prevent a complete intake draft from getting trapped in optional re-asks."""
    targets = set(clarify.field_targets)
    if not targets or not targets.issubset(_OPTIONAL_CLARIFY_TARGETS):
        return False
    if targets & _answered_optional_targets(history):
        return True
    return _answered_optional_turn_count(history) >= _MAX_OPTIONAL_CLARIFY_TURNS_AFTER_COMPLETE


def _fallback_clarify(draft: RunIntakeDraft) -> IntakeClarifyRequest:
    """Deterministic clarify question when the LLM output is unusable.

    Picks the FIRST missing required field so the run can still make progress
    without LLM. Bilingual labels: chip text is what users see; the wait-node
    normalizer handles label → internal value translation in one place.

    Invariant: caller MUST check `draft.is_complete` before delegating here.
    Falling through this function with an already-complete draft is what
    produced the "Agent repeats the same competitors question forever" bug
    (LLM 401 → fallback path → this function → hardcoded competitors prompt
    → user answers → next turn 401 again → same prompt). We `raise` to surface
    that contract violation loudly instead of silently re-asking.
    """
    if draft.user_role is None:
        return IntakeClarifyRequest(
            question="请问您在工作中更接近以下哪个角色？",
            field_targets=["user_role"],
            suggested_options=[
                "PM / 产品经理",
                "Founder / 创业者",
                "Sales / 销售",
                "Investor / 投资人",
            ],
        )
    if not (draft.analysis_intent and draft.analysis_intent.strip()):
        return IntakeClarifyRequest(
            question="请用一句话描述这次分析您最想了解什么？",
            field_targets=["analysis_intent"],
            suggested_answer="想了解目标赛道主要竞品的定价、功能与用户反馈差异。",
        )
    has_competitors_path = bool(draft.competitors_explicit) or (
        draft.competitors_discovery_mode is True
    )
    if not has_competitors_path:
        return IntakeClarifyRequest(
            question="您已经有想分析的竞品名单吗？没有的话可以让 Agent 帮您发现。",
            field_targets=["competitors_explicit", "competitors_discovery_mode"],
            suggested_options=["我已有名单 (explicit)", "让 Agent 帮我发现 (auto-discover)"],
        )
    raise RuntimeError(
        "intake._fallback_clarify called on a draft that already satisfies "
        "all required fields; caller must check draft.is_complete before "
        "falling back."
    )


def _merge_reply_into_draft(
    draft: RunIntakeDraft,
    clarify: IntakeClarifyRequest,
    reply: IntakeUserReply,
) -> RunIntakeDraft:
    """Deterministically merge a user reply into the draft based on field_targets.

    This is the critical fix for the "Agent repeats the same question" bug:
    previously the wait node only appended to history and trusted the next LLM
    turn to re-parse the reply. With unstable LLMs that turn would often emit
    an empty draft_patch, leaving required fields null and triggering a re-ask.

    Now any user reply whose target field can be unambiguously decoded from
    selected_options or free text is written into the draft immediately. The
    next LLM turn still gets to refine optional fields and decide complete/ask.
    """
    if not clarify.field_targets:
        return draft

    targets = set(clarify.field_targets)
    base = draft.model_dump(exclude={"is_complete"})
    candidates: list[str] = [*reply.selected_options, reply.text]
    combined = " ".join(c for c in candidates if c).strip()

    if "user_role" in targets and base.get("user_role") is None:
        for candidate in candidates:
            role = _match_keyword(candidate, _ROLE_KEYWORDS)
            if role is not None:
                base["user_role"] = role
                break

    if "analysis_intent" in targets:
        intent_current = base.get("analysis_intent")
        # Prefer free-text reply when present; fall back to selected_options join.
        if reply.text.strip():
            base["analysis_intent"] = reply.text.strip()
        elif not intent_current and reply.selected_options:
            base["analysis_intent"] = ", ".join(reply.selected_options)

    discovery_changed = False
    if "competitors_discovery_mode" in targets or "competitors_explicit" in targets:
        for candidate in candidates:
            lowered = candidate.casefold()
            if any(k.casefold() in lowered for k in _DISCOVERY_ON_KEYWORDS):
                base["competitors_discovery_mode"] = True
                discovery_changed = True
                break
            if any(k.casefold() in lowered for k in _DISCOVERY_OFF_KEYWORDS):
                base["competitors_discovery_mode"] = False
                discovery_changed = True
                break

    if "competitors_explicit" in targets and reply.text.strip():
        parsed = _split_competitor_list(reply.text)
        if parsed:
            existing = base.get("competitors_explicit") or []
            existing_keys = {c.casefold() for c in existing if isinstance(c, str)}
            merged = list(existing)
            for item in parsed:
                if item.casefold() not in existing_keys:
                    merged.append(item)
                    existing_keys.add(item.casefold())
            base["competitors_explicit"] = merged
            # User listed actual competitors → discovery_mode no longer required
            # unless they also explicitly opted in.
            if not discovery_changed:
                base["competitors_discovery_mode"] = base.get("competitors_discovery_mode", False)

    option_text = "、".join(option.strip() for option in reply.selected_options if option.strip())
    reply_signal = reply.text.strip() or option_text

    if "domain_hint" in targets and reply_signal:
        canonical_domain = _canonical_domain_hint(reply_signal)
        base["domain_hint"] = canonical_domain
        rewritten_intent = _rewrite_ambiguous_intent(
            current_intent=base.get("analysis_intent"),
            canonical_domain=canonical_domain,
        )
        if rewritten_intent is not None:
            base["analysis_intent"] = rewritten_intent
    if "target_category" in targets and reply_signal:
        base["target_category"] = normalize_optional_text(reply_signal)

    # Optional free-text enrichment fields: accept the user's phrasing verbatim
    # when the Agent's clarify question targeted one of them.
    for free_text_field in _OPTIONAL_FREE_TEXT_TARGETS - {"domain_hint", "target_category"}:
        if free_text_field in targets and reply_signal:
            base[free_text_field] = normalize_optional_text(reply_signal)

    # focus_dimensions / reference_urls intentionally left to the LLM —
    # they need richer parsing the wait node should not own.
    _ = combined  # reserved for future heuristic; keep variable to signal intent

    return RunIntakeDraft.model_validate(base)


async def _persist_intake_step(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    turn: int,
    action: str,
    draft: RunIntakeDraft,
    clarify: IntakeClarifyRequest | None,
    llm_response: LLMResponse,
    reasoning_summary: str,
) -> str:
    async with session_factory() as session:
        step = Step(
            step_id=make_id("step_"),
            run_id=run_id,
            agent_name="intake_agent",
            status="running",
            retry_count=0,
            payload={
                "phase": "intake",
                "turn": turn,
                "action": action,
                "draft_complete": bool(draft.is_complete),
                "clarify_field_targets": list(clarify.field_targets) if clarify else [],
                "llm_provider": llm_response.provider,
                "llm_fallback_used": llm_response.fallback_used,
                "llm_fallback_reason": llm_response.fallback_reason,
                "reasoning_summary": reasoning_summary[:1000] if reasoning_summary else "",
            },
        )
        session.add(step)
        await session.flush()
        session.add(build_llm_call_record(step_id=step.step_id, response=llm_response))
        step.status = "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
        return step.step_id


@log_node("intake_generate")
async def intake_generate_node(state: AgentState) -> AgentState:
    """LLM-driven intake turn. Decides ask vs. complete and writes pending_clarify.

    Invariant A: this node is the *generate* half of the split. All side effects
    (LLM call, Step+LLMCall persistence, INTAKE_* events) happen here so they
    are committed before the wait node's interrupt(). Resumes after interrupt
    re-execute only the wait node, never this one.
    """
    session_factory = _resolve_session_factory(state)
    run_id = state.get("run_id") or make_id("run_")
    user_query = state.get("user_query") or ""
    draft = _ensure_response_language(coerce_intake_draft_or_default(state), user_query)
    history = coerce_intake_history(state)
    turn = len(history) + 1
    draft_dump = draft.model_dump(exclude={"is_complete"})

    user_prompt = build_intake_user_prompt(
        user_query=user_query,
        current_draft=draft_dump,
        history=_history_to_prompt(history),
    )
    fallback_user_prompt = build_intake_fallback_user_prompt(
        user_query=user_query,
        current_draft=draft_dump,
    )
    harness_result = await complete_structured(
        model_slot="research",
        system_prompt=INTAKE_SYSTEM_PROMPT,
        user_prompt=user_prompt,
        output_model=IntakeTurnOutput,
        parser=IntakeTurnOutput.parse_llm_content,
        fallback_system_prompt=INTAKE_SYSTEM_PROMPT,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=lambda errors: build_intake_repair_user_prompt(
            validation_errors=errors,
            user_query=user_query,
            current_draft=draft_dump,
        ),
        log_event="intake.harness.finish",
    )
    llm_response = harness_result.llm_response

    parsed_turn = harness_result.value
    if parsed_turn is not None:
        action_raw = parsed_turn.action
        patch = parsed_turn.draft_patch
        next_draft = _apply_patch(draft, patch)
        parsed_clarify = (
            parsed_turn.clarify_request.to_request() if parsed_turn.clarify_request else None
        )
        reasoning_summary = parsed_turn.reasoning_summary
        title_content: dict[str, object] = {"summary_title": parsed_turn.summary_title}
    else:
        action_raw = None
        patch = {}
        next_draft = draft
        parsed_clarify = None
        reasoning_summary = ""
        title_content = {}

    # report_depth is owned by the dedicated planning profile gate. If the LLM
    # still asks it here, drop the clarify turn and continue normal routing.
    if action_raw == "ask" and parsed_clarify is not None:
        if "report_depth" in parsed_clarify.field_targets:
            log.info(
                "intake.generate.ask_blocked_report_depth_target",
                run_id=run_id,
                dropped_field_targets=list(parsed_clarify.field_targets),
            )
            parsed_clarify = None

    # When the merged draft is already complete, redundant required re-asks and
    # repeated optional re-asks are noise. Drop them so intake can hand off to
    # planning instead of trapping the user in clarification loops.
    if (
        action_raw == "ask"
        and parsed_clarify is not None
    ):
        unsatisfied_targets = _unsatisfied_clarify_targets(parsed_clarify, next_draft)
        drop_reason: str | None = None
        if not unsatisfied_targets:
            drop_reason = "targets_already_satisfied"
        elif _should_drop_optional_clarify(parsed_clarify, history):
            drop_reason = "optional_repeat_or_limit"

        if drop_reason is not None:
            log.info(
                "intake.generate.ask_dropped_complete_draft",
                run_id=run_id,
                dropped_field_targets=list(parsed_clarify.field_targets),
                unsatisfied_targets=unsatisfied_targets,
                reason=drop_reason,
            )
            parsed_clarify = None

    if next_draft.is_complete and _needs_ambiguous_term_clarify(
        draft=next_draft,
        history=history,
    ):
        action_raw = "ask"
        parsed_clarify = _ambiguous_term_clarify(next_draft)
        log.info(
            "intake.generate.ambiguous_term_clarify_forced",
            run_id=run_id,
            terms=sorted(_AMBIGUOUS_TERMS),
        )

    # Decision order matters. The key invariant: if the merged draft already
    # satisfies all required fields, the run MUST move to `complete` regardless
    # of what the LLM said. Without this, an unstable LLM (e.g. provider 401,
    # malformed JSON) used to drop us into `_fallback_clarify`, which would
    # then re-ask a hardcoded question on a fully-populated draft — creating
    # the "Agent repeats the same competitors prompt forever" loop.
    if action_raw == "complete" and next_draft.is_complete:
        action: str = "complete"
        clarify: IntakeClarifyRequest | None = None
    elif action_raw == "ask" and parsed_clarify is not None:
        action = "ask"
        clarify = parsed_clarify
    elif next_draft.is_complete:
        action = "complete"
        clarify = None
        log.warning(
            "intake.generate.forced_complete",
            run_id=run_id,
            reason="llm_unusable_but_draft_complete",
            llm_action=action_raw,
            llm_fallback_used=llm_response.fallback_used,
        )
    else:
        # LLM output unusable AND draft genuinely incomplete → ask the next
        # missing required field deterministically. _fallback_clarify raises
        # if the draft is already complete; the branch above is what guards
        # against that, so reaching here means a real missing field exists.
        action = "ask"
        clarify = _fallback_clarify(next_draft)

    summary_title = (
        _extract_summary_title(title_content, next_draft, user_query) if action == "complete" else None
    )

    step_id = await _persist_intake_step(
        session_factory=session_factory,
        run_id=run_id,
        turn=turn,
        action=action,
        draft=next_draft,
        clarify=clarify,
        llm_response=llm_response,
        reasoning_summary=reasoning_summary,
    )
    with bind_step(step_id):
        log.info(
            "intake.generate",
            run_id=run_id,
            turn=turn,
            action=action,
            draft_complete=bool(next_draft.is_complete),
            llm_provider=llm_response.provider,
            llm_fallback_used=llm_response.fallback_used,
        )

    if action == "complete":
        await _persist_intake_draft_to_run(run_id=run_id, draft=next_draft)
        if summary_title is not None:
            await _persist_run_title(run_id=run_id, title=summary_title)
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.INTAKE_COMPLETE,
            step_id=step_id,
            payload={
                "turn": turn,
                "draft": next_draft.model_dump(exclude={"is_complete"}),
                "title": summary_title,
            },
        )
        # Phase 2: intake.complete hands off to the planner. The graph's
        # _route_after_intake_generate reads `phase` and routes to planner_generate.
        return {
            **spread_without_accumulators(state),
            "run_id": run_id,
            "phase": "planning",
            "report_depth_selection_pending": True,
            "intake_draft": next_draft,
            "domain_hint": next_draft.domain_hint,
            "market_scope": next_draft.market_scope,
            "response_language": next_draft.response_language,
            "intake_history": history,
            "pending_clarify": None,
        }

    assert clarify is not None  # narrowing for type checker; action=="ask" guarantees this
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.INTAKE_CLARIFY_REQUEST,
        step_id=step_id,
        payload={
            "turn": turn,
            "question": clarify.question,
            "field_targets": list(clarify.field_targets),
            "suggested_options": list(clarify.suggested_options or []),
            "suggested_answer": clarify.suggested_answer,
            "draft_complete": bool(next_draft.is_complete),
            # Phase 1b fix: include the live draft so FE can update the
            # requirement checklist immediately (was previously relying on a
            # racey GET /api/runs/{id} from the SSE handler).
            "draft": next_draft.model_dump(exclude={"is_complete"}),
        },
    )
    return {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "phase": "intake",
        "report_depth_selection_pending": False,
        "intake_draft": next_draft,
        "domain_hint": next_draft.domain_hint,
        "market_scope": next_draft.market_scope,
        "response_language": next_draft.response_language,
        "intake_history": history,
        "pending_clarify": clarify,
    }


def _coerce_pending_clarify(state: AgentState) -> IntakeClarifyRequest:
    """Read pending_clarify from state; raises if generate_node didn't set it.

    Fails fast at the boundary instead of silently injecting a fake clarify —
    a missing pending_clarify means the graph topology is wrong, not a recoverable input.
    """
    pending = state.get("pending_clarify")
    if isinstance(pending, IntakeClarifyRequest):
        return pending
    if isinstance(pending, dict):
        return IntakeClarifyRequest.model_validate(pending)
    raise RuntimeError(
        "intake_wait_node entered without pending_clarify in state; check graph wiring."
    )


@log_node("intake_wait")
async def intake_wait_node(state: AgentState) -> AgentState:
    """Pure interrupt node. Idempotent: on replay it just re-issues interrupt().

    Invariant A: this node carries NO LLM calls, NO DB writes before interrupt().
    All side effects after interrupt() run exactly once per resume.
    """
    clarify = _coerce_pending_clarify(state)
    raw_reply: Any = interrupt(clarify.model_dump())

    try:
        reply = IntakeUserReply.model_validate(raw_reply)
    except ValidationError as exc:
        # Re-raise as RuntimeError; the resume endpoint is the only writer of resume
        # values and must validate them before passing Command(resume=...). Reaching here
        # means the endpoint contract was bypassed.
        raise RuntimeError(f"intake_wait resume value failed validation: {exc}") from exc

    run_id = state.get("run_id") or make_id("run_")
    history = coerce_intake_history(state)
    history = [*history, IntakeExchange(clarify=clarify, reply=reply)]

    # CRITICAL FIX: merge the reply into the draft right here, NOT in the next
    # generate turn. Letting the LLM be the only writer of draft fields means
    # any flaky LLM turn drops user-provided info and re-asks the same question.
    # The wait node owns the deterministic floor; the LLM enriches on top.
    current_draft = coerce_intake_draft_or_default(state)
    next_draft = _merge_reply_into_draft(current_draft, clarify, reply)

    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.INTAKE_USER_REPLY,
        step_id=None,
        payload={
            "turn": len(history),
            "reply_text": reply.text,
            "reply_options": list(reply.selected_options),
            # Mirror the post-merge draft so the FE checklist updates the moment
            # the user sends, without waiting for the next clarify turn.
            "draft": next_draft.model_dump(exclude={"is_complete"}),
        },
    )

    return {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "phase": "intake",
        "intake_draft": next_draft,
        "intake_history": history,
        "pending_clarify": None,
    }

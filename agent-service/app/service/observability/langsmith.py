from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import asdict, is_dataclass
from typing import Any, Iterator

from langsmith import Client
from langsmith.run_helpers import traceable, tracing_context

from core.config import settings
from utils.logger import get_logger

log = get_logger("service.observability.langsmith")

_trace_metadata: ContextVar[dict[str, Any]] = ContextVar(
    "agent_langsmith_trace_metadata", default={}
)


def _enabled() -> bool:
    return bool(settings.LANGSMITH_TRACING_ENABLED and settings.LANGSMITH_API_KEY)


def _build_client(*, sample_rate: float | None = None) -> Client | None:
    if not settings.LANGSMITH_API_KEY:
        return None
    try:
        return Client(
            api_url=settings.LANGSMITH_ENDPOINT,
            api_key=settings.LANGSMITH_API_KEY,
            tracing_sampling_rate=(
                settings.LANGSMITH_SAMPLE_RATE if sample_rate is None else sample_rate
            ),
        )
    except Exception as exc:  # pragma: no cover - depends on SDK/environment setup
        log.warning("langsmith.client.setup_failed", error=str(exc)[:300])
        return None


_client = _build_client()
_evaluation_client = _build_client(sample_rate=1.0)


def langsmith_client_for_source(source: str | None) -> Client | None:
    if source == "eval" and settings.LANGSMITH_EVALUATION_ENABLED:
        return _evaluation_client
    return _client


def _safe_value(value: Any, *, limit: int = 4000) -> Any:
    if isinstance(value, str):
        # Import lazily to avoid the service.llm package re-entering its client
        # while service.observability is being imported.
        from service.llm.trace import sanitize_trace_text

        return sanitize_trace_text(value, limit=limit)
    if isinstance(value, dict):
        return {str(key): _safe_value(item, limit=limit) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_safe_value(item, limit=limit) for item in value[:100]]
    if is_dataclass(value):
        return _safe_value(asdict(value), limit=limit)
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return str(value)[:limit]


def _process_inputs(inputs: Any) -> Any:
    return _safe_value(inputs)


def _process_outputs(outputs: Any) -> Any:
    return _safe_value(outputs)


def current_trace_metadata() -> dict[str, Any]:
    """Return metadata inherited by nested node and LLM spans."""

    return dict(_trace_metadata.get())


@contextmanager
def trace_metadata_context(**metadata: Any) -> Iterator[None]:
    """Bind bounded run metadata for all nested LangSmith spans."""

    merged = {**current_trace_metadata(), **metadata}
    token = _trace_metadata.set(merged)
    try:
        yield
    finally:
        _trace_metadata.reset(token)


def trace_metadata_from_state(state: Any, *, node_name: str | None = None) -> dict[str, Any]:
    """Build stable, non-sensitive metadata from an AgentState-like value."""

    if not isinstance(state, dict):
        return {"node": node_name} if node_name else {}
    draft = state.get("intake_draft")
    if isinstance(draft, dict):
        draft_dict = draft
    elif hasattr(draft, "model_dump"):
        try:
            dumped = draft.model_dump(exclude={"is_complete"})
        except Exception:
            dumped = {}
        draft_dict = dumped if isinstance(dumped, dict) else {}
    else:
        draft_dict = {}
    user_query = state.get("user_query") or draft_dict.get("user_query")
    domain_hint = state.get("domain_hint") or draft_dict.get("domain_hint")
    analysis_intent = draft_dict.get("analysis_intent")
    user_role = draft_dict.get("user_role")
    try:
        from schemas.intake import infer_research_mode

        research_mode = infer_research_mode(
            user_query=user_query if isinstance(user_query, str) else None,
            domain_context=domain_hint if isinstance(domain_hint, str) else None,
            analysis_intent=analysis_intent if isinstance(analysis_intent, str) else None,
            user_role=user_role if isinstance(user_role, str) else None,
            analysis_archetype=draft_dict.get("analysis_archetype")
            if isinstance(draft_dict.get("analysis_archetype"), str)
            else None,
            explicit_mode=draft_dict.get("research_mode")
            if isinstance(draft_dict.get("research_mode"), str)
            else None,
        )
    except Exception:
        research_mode = "general"
    metadata: dict[str, Any] = {
        "run_id": state.get("run_id"),
        "research_mode": research_mode,
        "analysis_archetype": draft_dict.get("analysis_archetype")
        or state.get("analysis_archetype"),
        "report_depth": draft_dict.get("report_depth") or state.get("report_depth"),
        "response_language": draft_dict.get("response_language")
        or state.get("response_language"),
        "source": state.get("source") or "ui",
    }
    if isinstance(user_query, str):
        metadata["request_summary"] = user_query[:240]
    if node_name:
        metadata["node"] = node_name
    return {key: value for key, value in metadata.items() if value is not None}


@traceable(
    name="agent.llm.call",
    run_type="llm",
    client=_client,
    process_inputs=_process_inputs,
    process_outputs=_process_outputs,
)
async def traceable_llm_call(
    *,
    provider: Any,
    provider_name: str,
    model_slot: str,
    model_name: str,
    system_prompt: str,
    user_prompt: str,
    timeout_seconds: int,
    max_tokens: int | None,
    retry_count: int = 0,
    metadata: dict[str, Any] | None = None,
) -> Any:
    """Execute a provider call inside a LangSmith LLM span.

    Provider transports remain behind this adapter so retry, fallback and usage
    accounting stay owned by the Agent service while LangSmith receives a
    standard LLM-shaped trace.
    """

    return await provider.complete_json(
        system_prompt=system_prompt,
        user_prompt=user_prompt,
        model=model_name,
        timeout_seconds=timeout_seconds,
        max_tokens=max_tokens,
    )


@traceable(
    name="agent.langgraph.run",
    run_type="chain",
    client=_client,
    process_inputs=_process_inputs,
    process_outputs=_process_outputs,
)
async def traceable_graph_call(
    *,
    graph: Any,
    graph_input: object,
    config: dict[str, object],
    metadata: dict[str, Any] | None = None,
) -> Any:
    state = graph_input if isinstance(graph_input, dict) else {}
    inherited = trace_metadata_from_state(state)
    if metadata:
        inherited = {**inherited, **metadata}
    with trace_metadata_context(**inherited):
        return await graph.ainvoke(graph_input, config=config)


@traceable(
    name="agent.langgraph.node",
    run_type="chain",
    client=_client,
    process_inputs=_process_inputs,
    process_outputs=_process_outputs,
)
async def traceable_node_call(*, node_name: str, node: Any, state: Any) -> Any:
    """Execute one graph node as a child span of the current graph run."""

    metadata = trace_metadata_from_state(state, node_name=node_name)
    parent = current_trace_metadata()
    if parent.get("research_mode") not in {None, "general"} and metadata.get("research_mode") == "general":
        metadata.pop("research_mode", None)
    if parent.get("source") not in {None, "ui"} and metadata.get("source") == "ui":
        metadata.pop("source", None)
    with trace_metadata_context(**metadata):
        return await node(state)


@contextmanager
def langsmith_tracing_context() -> Iterator[None]:
    """Enable/disable LangSmith for the current async context without failing open."""

    # Keep exceptions from the application body untouched. Only tracing setup
    # and teardown failures are swallowed so retry/error handling remains valid.
    context = tracing_context(
        enabled=_enabled(),
        project_name=settings.LANGSMITH_PROJECT,
    )
    try:
        context.__enter__()
    except Exception as exc:  # pragma: no cover - SDK/network failures are fail-open
        log.warning("langsmith.context.setup_failed", error=str(exc)[:300])
        yield
        return

    try:
        yield
    except BaseException as exc:
        try:
            context.__exit__(type(exc), exc, exc.__traceback__)
        except Exception as teardown_exc:  # pragma: no cover - SDK/network failures
            log.warning("langsmith.context.teardown_failed", error=str(teardown_exc)[:300])
        raise
    else:
        try:
            context.__exit__(None, None, None)
        except Exception as exc:  # pragma: no cover - SDK/network failures are fail-open
            log.warning("langsmith.context.teardown_failed", error=str(exc)[:300])

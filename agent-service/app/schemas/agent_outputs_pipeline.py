from __future__ import annotations

from typing import Literal, Self, cast

from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

from core.defaults import (
    DEFAULT_FOCUS_DIMENSIONS,
    MAX_DISCOVERY_COMPETITORS,
    MAX_FOCUS_DIMENSIONS,
    PLAN_TASK_TITLE_MAX_LEN,
    MAX_RESEARCH_COMPETITORS,
    MAX_TOTAL_PLAN_TASKS,
)
from schemas.contracts import (
    ensure_comparison_schema_dimensions,
    normalize_dimension_or_none,
    research_focus_dimensions,
    validate_dimension,
    validate_token_list,
)
from schemas.intake import IntakeClarifyRequest, RunIntakeDraft
from schemas.plan import PlanTask, PlanTaskStage
from schemas.supervisor import (
    Analyze,
    ConductResearch,
    ConductResearchBatch,
    DiscoverCompetitors,
    Finalize,
    Write,
)
from service.llm.prompts import QA_SEMANTIC_ALLOWED_REJECT_TO, SKILL_CURATOR_ALLOWED_TYPES

from schemas.agent_outputs import stable_unique

IntakeAction = Literal["ask", "complete"]
SupervisorToolName = Literal[
    "DiscoverCompetitors",
    "ConductResearch",
    "ConductResearchBatch",
    "Analyze",
    "Write",
    "Finalize",
]
ResearcherActionName = Literal[
    "search_web",
    "fetch_url",
    "extract_structured",
    "load_skill",
    "read_skill_file",
    "finalize",
]
QASeverity = Literal["blocking", "warning"]
SkillCuratorCandidateType = Literal["qa_rule", "prompt_template", "source_routing"]
DiscoveryCandidateRole = Literal[
    "direct_competitor",
    "adjacent_competitor",
    "substitute",
    "upstream_supplier",
    "trend_reference",
]

INTAKE_PATCHABLE_FIELDS: frozenset[str] = frozenset(
    {
        "user_role",
        "analysis_intent",
        "competitors_explicit",
        "competitors_discovery_mode",
        "domain_hint",
        "target_category",
        "category_aliases",
        "excluded_categories",
        "market_segments",
        "scope_policy",
        "focus_dimensions",
        "reference_urls",
        "self_product",
        "market_scope",
        "time_context",
        "response_language",
        "analysis_archetype",
    }
)
PLANNER_VALID_STAGES: frozenset[str] = frozenset({"discover", "research", "analyze", "write"})
SUPERVISOR_VALID_TOOLS: frozenset[str] = frozenset(
    {
        "DiscoverCompetitors",
        "ConductResearch",
        "ConductResearchBatch",
        "Analyze",
        "Write",
        "Finalize",
    }
)
DISCOVERY_MIN_COMPETITORS = 0


class IntakeClarifyOutput(BaseModel):
    question: str = Field(min_length=1)
    field_targets: list[str] = Field(default_factory=list)
    suggested_options: list[str] | None = None
    suggested_answer: str | None = None

    def to_request(self) -> IntakeClarifyRequest:
        return IntakeClarifyRequest(
            question=self.question,
            field_targets=list(self.field_targets),
            suggested_options=self.suggested_options,
            suggested_answer=self.suggested_answer,
        )


class IntakeTurnOutput(BaseModel):
    action: IntakeAction
    draft_patch: dict[str, object] = Field(default_factory=dict)
    clarify_request: IntakeClarifyOutput | None = None
    summary_title: str | None = None
    reasoning_summary: str = ""

    @model_validator(mode="after")
    def _validate_action_clarify(self) -> Self:
        if self.action == "ask" and self.clarify_request is None:
            raise ValueError("clarify_request is required when action=ask")
        if self.action == "complete" and self.clarify_request is not None:
            raise ValueError("clarify_request must be null when action=complete")
        return self

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> IntakeTurnOutput:
        action_raw = content.get("action")
        if action_raw not in {"ask", "complete"}:
            raise ValueError("action must be ask or complete")
        patch_raw = content.get("draft_patch")
        sanitized: dict[str, object] = {}
        if isinstance(patch_raw, dict):
            for key, value in patch_raw.items():
                if key in INTAKE_PATCHABLE_FIELDS and value is not None:
                    sanitized[key] = value
        clarify: IntakeClarifyOutput | None = None
        clarify_raw = content.get("clarify_request")
        if isinstance(clarify_raw, dict):
            clarify = IntakeClarifyOutput.model_validate(clarify_raw)
        reasoning_raw = content.get("reasoning_summary")
        reasoning = reasoning_raw.strip() if isinstance(reasoning_raw, str) else ""
        summary_title_raw = content.get("summary_title")
        summary_title = (
            summary_title_raw.strip()
            if isinstance(summary_title_raw, str) and summary_title_raw.strip()
            else None
        )
        return cls.model_validate(
            {
                "action": action_raw,
                "draft_patch": sanitized,
                "clarify_request": clarify,
                "summary_title": summary_title,
                "reasoning_summary": reasoning,
            }
        )


class PlannerTaskDraft(BaseModel):
    stage: PlanTaskStage
    title: str = Field(min_length=1)
    description: str = ""
    competitor_id: str | None = None
    focus_dimensions: list[str] = Field(default_factory=list)


class PlannerOutput(BaseModel):
    rationale: str = ""
    tasks: list[PlannerTaskDraft] = Field(min_length=1)

    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        draft: RunIntakeDraft,
    ) -> PlannerOutput:
        tasks_raw = content.get("tasks")
        if not isinstance(tasks_raw, list) or not tasks_raw:
            raise ValueError("tasks must be a non-empty list")
        default_focus = (
            validate_token_list(
                values=list(draft.focus_dimensions),
                field_name="draft.focus_dimensions",
                item_validator=validate_dimension,
                allow_empty=True,
            )[:MAX_FOCUS_DIMENSIONS]
            or list(DEFAULT_FOCUS_DIMENSIONS)
        )
        default_focus = ensure_comparison_schema_dimensions(
            default_focus,
            analysis_archetype=draft.analysis_archetype,
        )[:MAX_FOCUS_DIMENSIONS]
        parsed_tasks: list[PlannerTaskDraft] = []
        research_count = 0
        analyze_count = 0
        max_analyze_tasks = 2 if draft.analysis_archetype == "landscape" else 1
        for item in tasks_raw:
            if not isinstance(item, dict):
                continue
            stage_raw = item.get("stage")
            if not isinstance(stage_raw, str) or stage_raw not in PLANNER_VALID_STAGES:
                continue
            title_raw = item.get("title")
            if not isinstance(title_raw, str) or not title_raw.strip():
                continue
            description_raw = item.get("description")
            description = description_raw.strip() if isinstance(description_raw, str) else ""
            competitor_raw = item.get("competitor_id")
            competitor_id = (
                competitor_raw.strip()
                if isinstance(competitor_raw, str) and competitor_raw.strip()
                else None
            )
            if stage_raw == "research":
                if competitor_id is None:
                    continue
                if research_count >= MAX_RESEARCH_COMPETITORS:
                    continue
                research_count += 1
            if stage_raw == "analyze":
                if analyze_count >= max_analyze_tasks:
                    continue
                analyze_count += 1
            focus_raw = item.get("focus_dimensions")
            if isinstance(focus_raw, list):
                focus = validate_token_list(
                    values=[
                        str(v).strip()
                        for v in focus_raw
                        if isinstance(v, str) and v.strip()
                    ],
                    field_name="tasks.focus_dimensions",
                    item_validator=validate_dimension,
                    allow_empty=True,
                )[:MAX_FOCUS_DIMENSIONS]
            else:
                focus = list(default_focus)
            if not focus:
                focus = list(default_focus)
            if stage_raw in {"discover", "research"}:
                # Derived dimensions are analyst-synthesized, not independently
                # gathered; keep them out of research/discovery focus (R9).
                focus = research_focus_dimensions(
                    focus,
                    analysis_archetype=draft.analysis_archetype,
                )
            parsed_tasks.append(
                PlannerTaskDraft(
                    stage=cast(PlanTaskStage, stage_raw),
                    title=title_raw.strip()[:PLAN_TASK_TITLE_MAX_LEN],
                    description=description,
                    competitor_id=competitor_id,
                    focus_dimensions=focus,
                )
            )
            if len(parsed_tasks) >= MAX_TOTAL_PLAN_TASKS:
                break
        if not parsed_tasks:
            raise ValueError("No valid planner tasks remain after validation")
        rationale_raw = content.get("rationale")
        rationale = rationale_raw.strip() if isinstance(rationale_raw, str) else ""
        return cls(rationale=rationale, tasks=parsed_tasks)

    def to_plan_tasks(self) -> list[PlanTask]:
        return [
            PlanTask(
                stage=task.stage,
                title=task.title,
                description=task.description,
                competitor_id=task.competitor_id,
                focus_dimensions=list(task.focus_dimensions),
                source="agent",
                enabled=True,
                priority="normal",
            )
            for task in self.tasks
        ]


class ReplannerOutput(PlannerOutput):
    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        draft: RunIntakeDraft,
    ) -> ReplannerOutput:
        planner_output = PlannerOutput.parse_llm_content(content, draft=draft)
        return cls(rationale=planner_output.rationale, tasks=planner_output.tasks)


class SupervisorToolCallOutput(BaseModel):
    chosen_tool: SupervisorToolName
    tool_args: dict[str, object]
    reasoning_summary: str = Field(min_length=1)

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> SupervisorToolCallOutput:
        chosen_tool_raw = content.get("chosen_tool")
        if not isinstance(chosen_tool_raw, str) or chosen_tool_raw not in SUPERVISOR_VALID_TOOLS:
            raise ValueError("chosen_tool is invalid")
        tool_args_raw = content.get("tool_args")
        if not isinstance(tool_args_raw, dict):
            raise ValueError("tool_args must be an object")
        chosen_tool = cast(SupervisorToolName, chosen_tool_raw)
        if chosen_tool == "DiscoverCompetitors":
            tool_args = DiscoverCompetitors.model_validate(tool_args_raw).model_dump()
        elif chosen_tool == "ConductResearch":
            tool_args = ConductResearch.model_validate(tool_args_raw).model_dump()
        elif chosen_tool == "ConductResearchBatch":
            batch_args = ConductResearchBatch.model_validate(tool_args_raw)
            topic_competitors = [topic.competitor_id for topic in batch_args.topics]
            if len(set(topic_competitors)) != len(topic_competitors):
                raise ValueError("ConductResearchBatch topics must have unique competitor_id")
            tool_args = batch_args.model_dump()
        elif chosen_tool == "Analyze":
            tool_args = Analyze.model_validate(tool_args_raw).model_dump()
        elif chosen_tool == "Write":
            tool_args = Write.model_validate(tool_args_raw).model_dump()
        else:
            tool_args = Finalize.model_validate(tool_args_raw).model_dump()
        reasoning_raw = content.get("reasoning_summary")
        if not isinstance(reasoning_raw, str) or not reasoning_raw.strip():
            raise ValueError("reasoning_summary is required")
        return cls(
            chosen_tool=chosen_tool,
            tool_args=tool_args,
            reasoning_summary=reasoning_raw.strip(),
        )


class DiscoveryCompetitorCandidate(BaseModel):
    name: str = Field(min_length=1)
    is_competitor: bool = True
    candidate_role: DiscoveryCandidateRole | None = None
    relevance_reason: str = ""
    evidence_quote: str = ""
    official_url: str | None = None
    source_domain: str | None = None
    # Product-level profile fields. `segment` is the sub-track the product belongs
    # to (e.g. "AI眼镜"), enabling 赛道→产品 grouping; `introduction` is a one-line
    # "what is this" blurb; `vendor` is the parent company/brand behind the product.
    segment: str | None = None
    introduction: str | None = None
    vendor: str | None = None

    @field_validator("name", "relevance_reason", "evidence_quote", mode="before")
    @classmethod
    def _normalize_text(cls, value: object) -> str:
        if value is None:
            return ""
        return str(value).strip()

    @field_validator("official_url", "source_domain", "segment", "introduction", "vendor", mode="before")
    @classmethod
    def _normalize_optional_text(cls, value: object) -> str | None:
        if value is None:
            return None
        normalized = str(value).strip()
        return normalized or None


class DiscoveryExtractOutput(BaseModel):
    competitors: list[str] = Field(default_factory=list)
    candidates: list[DiscoveryCompetitorCandidate] = Field(default_factory=list)

    @field_validator("competitors")
    @classmethod
    def _normalize_competitors(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        for value in values:
            name = value.strip() if isinstance(value, str) else str(value).strip()
            if name and name not in normalized:
                normalized.append(name)
        return normalized[:MAX_DISCOVERY_COMPETITORS]

    @model_validator(mode="after")
    def _sync_competitors_from_candidates(self) -> Self:
        candidate_names = [
            candidate.name
            for candidate in self.candidates
            if candidate.name and candidate.is_competitor
        ]
        if candidate_names:
            self.competitors = stable_unique(candidate_names)[:MAX_DISCOVERY_COMPETITORS]
        return self

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> DiscoveryExtractOutput:
        competitors_raw = content.get("competitors")
        candidates_raw = content.get("candidates")
        source_rows = candidates_raw if isinstance(candidates_raw, list) else competitors_raw
        if not isinstance(source_rows, list):
            raise ValueError("competitors or candidates must be a list")

        names: list[str] = []
        candidates: list[dict[str, object]] = []
        for item in source_rows:
            if isinstance(item, dict):
                name_raw = item.get("name") or item.get("competitor") or item.get("product_name")
                if not isinstance(name_raw, str) or not name_raw.strip():
                    continue
                candidate = {
                    "name": name_raw,
                    "is_competitor": item.get("is_competitor", True),
                    "candidate_role": item.get("candidate_role") or item.get("role"),
                    "relevance_reason": item.get("relevance_reason", ""),
                    "evidence_quote": item.get("evidence_quote", ""),
                    "official_url": item.get("official_url"),
                    "source_domain": item.get("source_domain"),
                    "segment": item.get("segment") or item.get("track") or item.get("sub_category"),
                    "introduction": item.get("introduction") or item.get("description") or item.get("summary"),
                    "vendor": item.get("vendor") or item.get("company") or item.get("brand"),
                }
                candidates.append(candidate)
                continue
            if item:
                names.append(str(item))
                candidates.append(
                    {
                        "name": str(item),
                        "is_competitor": True,
                        "candidate_role": None,
                        "relevance_reason": "",
                        "evidence_quote": "",
                        "official_url": None,
                        "source_domain": None,
                        "segment": None,
                        "introduction": None,
                        "vendor": None,
                    }
                )
        return cls.model_validate({"competitors": names, "candidates": candidates})


class ResearcherDecisionOutput(BaseModel):
    action: ResearcherActionName
    action_args: dict[str, object] = Field(default_factory=dict)
    reasoning_summary: str = ""

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> ResearcherDecisionOutput:
        action_raw = content.get("action")
        if not isinstance(action_raw, str):
            raise ValueError("action is required")
        action_args_raw = content.get("action_args")
        action_args = action_args_raw if isinstance(action_args_raw, dict) else {}
        reasoning_raw = content.get("reasoning_summary")
        reasoning = reasoning_raw.strip() if isinstance(reasoning_raw, str) else ""
        return cls.model_validate(
            {
                "action": action_raw,
                "action_args": action_args,
                "reasoning_summary": reasoning,
            }
        )

    def to_action_tuple(
        self,
        *,
        competitor_id: str,
        focus_dimensions: list[str] | None = None,
        pending_dimensions: list[str] | None = None,
    ) -> tuple[str, dict[str, object]] | None:
        action = self.action
        action_args = dict(self.action_args)
        allowed_dimensions = focus_dimensions or []
        pending = pending_dimensions or []

        def _dimension_arg(*, fallback_to_pending: bool) -> str | None:
            dimension_raw = action_args.get("dimension")
            normalized, _ = normalize_dimension_or_none(
                dimension_raw,
                allowed=allowed_dimensions,
            )
            if normalized is not None:
                return normalized
            if fallback_to_pending and pending:
                return pending[0]
            return None

        if action == "finalize":
            return ("finalize", action_args)
        if action == "search_web":
            query_raw = action_args.get("query")
            max_results_raw = action_args.get("max_results")
            if isinstance(query_raw, str) and query_raw.strip():
                normalized: dict[str, object] = {"query": query_raw.strip()}
                if isinstance(max_results_raw, int):
                    normalized["max_results"] = max_results_raw
                dimension = _dimension_arg(fallback_to_pending=True)
                if dimension is not None:
                    normalized["dimension"] = dimension
                return ("search_web", normalized)
            return None
        if action == "fetch_url":
            url_raw = action_args.get("url")
            if isinstance(url_raw, str) and url_raw.strip():
                normalized = {"url": url_raw.strip(), "competitor_id": competitor_id}
                query_raw = action_args.get("query")
                if isinstance(query_raw, str) and query_raw.strip():
                    normalized["query"] = query_raw.strip()
                dimension = _dimension_arg(fallback_to_pending=False)
                if dimension is not None:
                    normalized["dimension"] = dimension
                return ("fetch_url", normalized)
            return None
        if action == "extract_structured":
            text_raw = action_args.get("text")
            if isinstance(text_raw, str) and text_raw.strip():
                normalized = {"text": text_raw}
                for key in ("source_url", "source_title"):
                    value = action_args.get(key)
                    if isinstance(value, str):
                        normalized[key] = value
                source_type_raw = action_args.get("source_type")
                if isinstance(source_type_raw, str):
                    normalized["source_type"] = source_type_raw
                dimension = _dimension_arg(fallback_to_pending=False)
                if dimension is not None:
                    normalized["dimension"] = dimension
                comp_raw = action_args.get("competitor_id")
                normalized["competitor_id"] = (
                    comp_raw.strip()
                    if isinstance(comp_raw, str) and comp_raw.strip()
                    else competitor_id
                )
                return ("extract_structured", normalized)
            return None
        if action == "load_skill":
            skill_id_raw = action_args.get("skill_id")
            if isinstance(skill_id_raw, str) and skill_id_raw.strip():
                return ("load_skill", {"skill_id": skill_id_raw.strip()})
            return None
        if action == "read_skill_file":
            skill_id_raw = action_args.get("skill_id")
            filename_raw = action_args.get("filename")
            if (
                isinstance(skill_id_raw, str)
                and skill_id_raw.strip()
                and isinstance(filename_raw, str)
                and filename_raw.strip()
            ):
                return (
                    "read_skill_file",
                    {"skill_id": skill_id_raw.strip(), "filename": filename_raw.strip()},
                )
            return None
        return None


class ResearcherCompressionOutput(BaseModel):
    compressed_summary: str = Field(min_length=1)

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> ResearcherCompressionOutput:
        summary_raw = content.get("compressed_summary")
        if not isinstance(summary_raw, str) or not summary_raw.strip():
            raise ValueError("compressed_summary is required")
        return cls(compressed_summary=summary_raw.strip())


class ExtractStructuredOutput(BaseModel):
    quote: str = Field(min_length=1)
    source_title: str | None = None

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> ExtractStructuredOutput:
        quote_raw = content.get("quote")
        if not isinstance(quote_raw, str) or not quote_raw.strip():
            raise ValueError("quote is required")
        title_raw = content.get("source_title")
        source_title = title_raw.strip() if isinstance(title_raw, str) and title_raw.strip() else None
        return cls(quote=quote_raw.strip(), source_title=source_title)


class UnsupportedNumericClaim(BaseModel):
    claim: str = Field(min_length=1)
    section_id: str = Field(min_length=1)
    reason: str = Field(min_length=1)


class QASemanticOutput(BaseModel):
    semantic_audit_passed: bool
    reject_to: Literal["supervisor", "researcher", "analyst", "writer"]
    severity: QASeverity
    finding: str = Field(min_length=1)
    required_fields: list[str] = Field(default_factory=list)
    dimension_results: dict[str, bool] = Field(default_factory=dict)
    unsupported_numeric_claims: list[UnsupportedNumericClaim] = Field(default_factory=list)

    @field_validator("reject_to")
    @classmethod
    def _validate_reject_to(cls, value: str) -> str:
        if value not in QA_SEMANTIC_ALLOWED_REJECT_TO:
            raise ValueError(f"reject_to must be one of {QA_SEMANTIC_ALLOWED_REJECT_TO}")
        return value

    @classmethod
    def parse_llm_content(cls, content: dict[str, object]) -> QASemanticOutput:
        normalized = dict(content)
        dimension_results_raw = normalized.get("dimension_results")
        if not isinstance(dimension_results_raw, dict):
            raise ValueError("dimension_results must be an object.")
        required_dimensions = (
            "depth",
            "citation_coverage",
            "faithfulness",
            "instruction_following",
        )
        normalized_dimension_results: dict[str, bool] = {}
        for dimension_key in required_dimensions:
            dimension_value = dimension_results_raw.get(dimension_key)
            if not isinstance(dimension_value, bool):
                raise ValueError(f"dimension_results.{dimension_key} must be bool.")
            normalized_dimension_results[dimension_key] = dimension_value
        normalized["dimension_results"] = normalized_dimension_results
        unsupported_raw = normalized.get("unsupported_numeric_claims")
        if unsupported_raw is None:
            normalized["unsupported_numeric_claims"] = []
        elif not isinstance(unsupported_raw, list):
            raise ValueError("unsupported_numeric_claims must be a list.")
        else:
            unsupported_items: list[dict[str, object]] = []
            for index, item in enumerate(unsupported_raw):
                if not isinstance(item, dict):
                    raise ValueError(
                        f"unsupported_numeric_claims[{index}] must be an object."
                    )
                claim = item.get("claim")
                section_id = item.get("section_id")
                reason = item.get("reason")
                if (
                    not isinstance(claim, str)
                    or not claim.strip()
                    or not isinstance(section_id, str)
                    or not section_id.strip()
                    or not isinstance(reason, str)
                    or not reason.strip()
                ):
                    raise ValueError(
                        f"unsupported_numeric_claims[{index}] must include non-empty claim/section_id/reason."
                    )
                unsupported_items.append(
                    {
                        "claim": claim.strip(),
                        "section_id": section_id.strip(),
                        "reason": reason.strip(),
                    }
                )
            normalized["unsupported_numeric_claims"] = unsupported_items
        return cls.model_validate(normalized)

    def to_normalized_dict(self) -> dict[str, object]:
        return {
            "semantic_audit_passed": self.semantic_audit_passed,
            "finding": self.finding,
            "reject_to": self.reject_to,
            "severity": self.severity,
            "required_fields": list(self.required_fields),
            "dimension_results": dict(self.dimension_results),
            "unsupported_numeric_claims": [
                item.model_dump(mode="python") for item in self.unsupported_numeric_claims
            ],
        }


class SkillCuratorCandidateOutput(BaseModel):
    candidate_type: SkillCuratorCandidateType
    tags: list[str] = Field(default_factory=list)
    payload: dict[str, object]
    rationale: str = Field(min_length=1)
    confidence: Literal["low", "medium", "high"] = "medium"
    supporting_run_ids: list[str] = Field(default_factory=list)

    @field_validator("candidate_type")
    @classmethod
    def _validate_candidate_type(cls, value: str) -> str:
        if value not in SKILL_CURATOR_ALLOWED_TYPES:
            raise ValueError(f"candidate_type must be one of {SKILL_CURATOR_ALLOWED_TYPES}")
        return value


class SkillCuratorHarnessOutput(BaseModel):
    candidates: list[SkillCuratorCandidateOutput] = Field(default_factory=list)

    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        allowed_types: frozenset[str],
    ) -> SkillCuratorHarnessOutput:
        candidates_raw = content.get("candidates")
        if not isinstance(candidates_raw, list):
            raise ValueError("candidates must be a list")
        parsed: list[SkillCuratorCandidateOutput] = []
        for item in candidates_raw:
            if not isinstance(item, dict):
                continue
            try:
                candidate = SkillCuratorCandidateOutput.model_validate(item)
            except ValidationError:
                continue
            if candidate.candidate_type not in allowed_types:
                continue
            parsed.append(candidate)
        return cls(candidates=parsed)

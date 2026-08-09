from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from db.engine import get_session_factory
from exceptions.base import APIException
from models.skill_candidate import SkillCandidateRecord
from service.skill_promotion import (
    PromotionRuleValidationError,
    PromotionWriteError,
    promote_approved_candidate,
)
from utils.logger import get_logger

router = APIRouter()
log = get_logger("router.skill_rt")


class SkillCandidateResponse(BaseModel):
    id: str
    candidate_type: str
    applies_to: str
    tags: list[str]
    payload: dict[str, object]
    rationale: str
    supporting_run_ids: list[str]
    confidence: str
    status: str
    reviewed_by: str | None
    reviewed_at: str | None
    error: str | None
    created_at: str


class SkillCandidateListResponse(BaseModel):
    items: list[SkillCandidateResponse]
    total: int
    limit: int
    offset: int


class SkillCandidateReviewRequest(BaseModel):
    reviewed_by: str = Field(default="human_reviewer", min_length=1, max_length=128)


class PromotedArtifactResponse(BaseModel):
    path: str
    action: str
    entry_id: str


class SkillCandidateReviewResponse(BaseModel):
    id: str
    status: str
    reviewed_by: str
    reviewed_at: str
    promoted_artifacts: list[PromotedArtifactResponse] = Field(default_factory=list)


def _to_iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return dt.isoformat()


def _to_item(record: SkillCandidateRecord) -> SkillCandidateResponse:
    return SkillCandidateResponse(
        id=record.id,
        candidate_type=record.candidate_type,
        applies_to=record.applies_to,
        tags=list(record.tags),
        payload=record.payload,
        rationale=record.rationale,
        supporting_run_ids=list(record.supporting_run_ids),
        confidence=record.confidence,
        status=record.status,
        reviewed_by=record.reviewed_by,
        reviewed_at=_to_iso(record.reviewed_at),
        error=record.error,
        created_at=record.created_at.isoformat(),
    )


def _skills_root() -> Path:
    return Path(__file__).resolve().parents[2] / "skills"


@router.get("/api/skill-candidates", response_model=SkillCandidateListResponse)
async def list_skill_candidates(
    status: str | None = Query(default=None),
    applies_to: str | None = Query(default=None),
    tag: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> SkillCandidateListResponse:
    normalized_status = status.strip() if isinstance(status, str) else None
    normalized_applies_to = applies_to.strip() if isinstance(applies_to, str) else None
    normalized_tag = tag.strip() if isinstance(tag, str) else None
    log.info(
        "api.skill.list.start",
        status=normalized_status,
        applies_to=normalized_applies_to,
        tag=normalized_tag,
        limit=limit,
        offset=offset,
    )

    session_factory = get_session_factory()
    async with session_factory() as session:
        list_query = select(SkillCandidateRecord)
        total_query = select(func.count()).select_from(SkillCandidateRecord)
        if normalized_status:
            list_query = list_query.where(SkillCandidateRecord.status == normalized_status)
            total_query = total_query.where(SkillCandidateRecord.status == normalized_status)
        if normalized_applies_to:
            list_query = list_query.where(SkillCandidateRecord.applies_to == normalized_applies_to)
            total_query = total_query.where(SkillCandidateRecord.applies_to == normalized_applies_to)
        if normalized_tag:
            list_query = list_query.where(SkillCandidateRecord.tags.contains([normalized_tag]))
            total_query = total_query.where(SkillCandidateRecord.tags.contains([normalized_tag]))

        list_query = list_query.order_by(SkillCandidateRecord.created_at.desc()).limit(limit).offset(offset)
        rows = (await session.execute(list_query)).scalars().all()
        total = int((await session.execute(total_query)).scalar_one())
    log.info("api.skill.list.finish", item_count=len(rows), total=total)

    return SkillCandidateListResponse(
        items=[_to_item(record) for record in rows],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.post("/api/skill-candidates/{candidate_id}/approve", response_model=SkillCandidateReviewResponse)
async def approve_skill_candidate(
    candidate_id: str,
    payload: SkillCandidateReviewRequest,
) -> SkillCandidateReviewResponse:
    log.info("api.skill.approve.start", candidate_id=candidate_id)
    session_factory = get_session_factory()
    promoted_artifacts: list[PromotedArtifactResponse] = []
    async with session_factory() as session:
        record = await session.get(SkillCandidateRecord, candidate_id)
        if record is None:
            raise APIException(
                status_code=404,
                error_code="SKILL_CANDIDATE_NOT_FOUND",
                message=f"candidate_id={candidate_id} does not exist",
            )
        if record.status != "staging":
            raise APIException(
                status_code=409,
                error_code="SKILL_CANDIDATE_NOT_REVIEWABLE",
                message=f"candidate_id={candidate_id} status={record.status} cannot be reviewed",
            )
        reviewed_at = datetime.now(timezone.utc)
        record.status = "approved"
        record.reviewed_by = payload.reviewed_by
        record.reviewed_at = reviewed_at
        try:
            promoted_artifact_rows = promote_approved_candidate(
                record=record,
                skills_root=_skills_root(),
                reviewed_by=payload.reviewed_by,
                reviewed_at=reviewed_at,
            )
        except PromotionRuleValidationError as exc:
            await session.rollback()
            raise APIException(
                status_code=422,
                error_code="SKILL_CANDIDATE_RULE_INVALID",
                message=f"invalid promoted QA rule candidate: {exc}",
            ) from exc
        except PromotionWriteError as exc:
            await session.rollback()
            raise APIException(
                status_code=500,
                error_code="PROMOTION_WRITE_FAILED",
                message=f"failed to write promoted skill artifacts: {exc}",
            ) from exc
        await session.commit()
        promoted_artifacts = [
            PromotedArtifactResponse.model_validate(item)
            for item in promoted_artifact_rows
        ]
    log.info("api.skill.approve.finish", candidate_id=candidate_id, reviewed_by=payload.reviewed_by)

    return SkillCandidateReviewResponse(
        id=candidate_id,
        status="approved",
        reviewed_by=payload.reviewed_by,
        reviewed_at=reviewed_at.isoformat(),
        promoted_artifacts=promoted_artifacts,
    )


@router.post("/api/skill-candidates/{candidate_id}/reject", response_model=SkillCandidateReviewResponse)
async def reject_skill_candidate(
    candidate_id: str,
    payload: SkillCandidateReviewRequest,
) -> SkillCandidateReviewResponse:
    log.info("api.skill.reject.start", candidate_id=candidate_id)
    session_factory = get_session_factory()
    async with session_factory() as session:
        record = await session.get(SkillCandidateRecord, candidate_id)
        if record is None:
            raise APIException(
                status_code=404,
                error_code="SKILL_CANDIDATE_NOT_FOUND",
                message=f"candidate_id={candidate_id} does not exist",
            )
        if record.status != "staging":
            raise APIException(
                status_code=409,
                error_code="SKILL_CANDIDATE_NOT_REVIEWABLE",
                message=f"candidate_id={candidate_id} status={record.status} cannot be reviewed",
            )
        reviewed_at = datetime.now(timezone.utc)
        record.status = "rejected"
        record.reviewed_by = payload.reviewed_by
        record.reviewed_at = reviewed_at
        await session.commit()
    log.info("api.skill.reject.finish", candidate_id=candidate_id, reviewed_by=payload.reviewed_by)

    return SkillCandidateReviewResponse(
        id=candidate_id,
        status="rejected",
        reviewed_by=payload.reviewed_by,
        reviewed_at=reviewed_at.isoformat(),
        promoted_artifacts=[],
    )

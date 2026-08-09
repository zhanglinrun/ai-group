from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.conclusion import ConclusionEvidenceLink, ConclusionRecord
from schemas.ids import make_id
from service.conclusion.mapper import MappedConclusion, comparisons_to_conclusions, insights_to_conclusions
from service.run_steps import latest_completed_step_id


def _add_conclusion_records(
    *,
    session: AsyncSession,
    run_id: str,
    step_id: str,
    mapped_items: list[MappedConclusion],
) -> list[ConclusionRecord]:
    records: list[ConclusionRecord] = []
    for mapped in mapped_items:
        record = ConclusionRecord(
            conclusion_id=make_id("concl_"),
            run_id=run_id,
            step_id=step_id,
            section=mapped["section"],
            claim=mapped["claim"],
            confidence=mapped["confidence"],
            competitor_ids=mapped["competitor_ids"],
            risk_flags=mapped["risk_flags"],
        )
        session.add(record)
        for rank, evidence_id in enumerate(mapped["evidence_ids"]):
            session.add(
                ConclusionEvidenceLink(
                    conclusion_id=record.conclusion_id,
                    evidence_id=evidence_id,
                    relevance_rank=rank,
                )
            )
        records.append(record)
    return records


async def persist_conclusions_for_step(
    *,
    session: AsyncSession,
    run_id: str,
    step_id: str,
    insights: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    risk_flags: list[str],
) -> list[ConclusionRecord]:
    mapped_items = insights_to_conclusions(
        run_id=run_id,
        step_id=step_id,
        insights=insights,
        evidence_lookup=evidence_lookup,
        risk_flags=risk_flags,
    )
    return _add_conclusion_records(
        session=session,
        run_id=run_id,
        step_id=step_id,
        mapped_items=mapped_items,
    )


async def persist_comparison_conclusions_for_step(
    *,
    session: AsyncSession,
    run_id: str,
    step_id: str,
    comparisons: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    competitors: list[str],
    covered_sections: set[str],
    risk_flags: list[str],
) -> list[ConclusionRecord]:
    mapped_items = comparisons_to_conclusions(
        run_id=run_id,
        step_id=step_id,
        comparisons=comparisons,
        evidence_lookup=evidence_lookup,
        competitors=competitors,
        covered_sections=covered_sections,
        risk_flags=risk_flags,
    )
    return _add_conclusion_records(
        session=session,
        run_id=run_id,
        step_id=step_id,
        mapped_items=mapped_items,
    )


async def load_conclusions_for_run(
    *,
    session: AsyncSession,
    run_id: str,
) -> list[dict[str, object]]:
    latest_analyst_step_id = await latest_completed_step_id(
        session=session,
        run_id=run_id,
        agent_name="analyst",
    )
    query = (
        select(ConclusionRecord)
        .where(ConclusionRecord.run_id == run_id)
        .order_by(ConclusionRecord.created_at.asc(), ConclusionRecord.conclusion_id.asc())
    )
    if latest_analyst_step_id is not None:
        query = query.where(ConclusionRecord.step_id == latest_analyst_step_id)
    conclusion_rows = (await session.execute(query)).scalars().all()
    if not conclusion_rows:
        return []

    conclusion_ids = [item.conclusion_id for item in conclusion_rows]
    link_rows = (
        await session.execute(
            select(ConclusionEvidenceLink)
            .where(ConclusionEvidenceLink.conclusion_id.in_(conclusion_ids))
            .order_by(
                ConclusionEvidenceLink.conclusion_id.asc(),
                ConclusionEvidenceLink.relevance_rank.asc(),
            )
        )
    ).scalars().all()
    evidence_ids_by_conclusion: dict[str, list[str]] = {}
    for link in link_rows:
        evidence_ids_by_conclusion.setdefault(link.conclusion_id, []).append(link.evidence_id)

    return [
        {
            "conclusion_id": row.conclusion_id,
            "run_id": row.run_id,
            "step_id": row.step_id,
            "section": row.section,
            "claim": row.claim,
            "confidence": row.confidence,
            "competitor_ids": list(row.competitor_ids),
            "risk_flags": list(row.risk_flags),
            "evidence_ids": evidence_ids_by_conclusion.get(row.conclusion_id, []),
            "created_at": row.created_at.isoformat(),
        }
        for row in conclusion_rows
    ]

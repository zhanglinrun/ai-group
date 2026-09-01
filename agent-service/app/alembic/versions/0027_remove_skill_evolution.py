"""remove generated skill candidate storage after the LangGraph cutover"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
from sqlalchemy.dialects.postgresql import JSONB
import sqlalchemy as sa

revision: str = "0027_remove_skill_evolution"
down_revision: str | None = "0026_billing_reservations"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.drop_table("skill_candidates")


def downgrade() -> None:
    op.create_table(
        "skill_candidates",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("candidate_type", sa.String(length=32), nullable=False),
        sa.Column("applies_to", sa.String(length=32), nullable=False),
        sa.Column("tags", JSONB, nullable=False),
        sa.Column("payload", JSONB, nullable=False),
        sa.Column("rationale", sa.Text(), nullable=False),
        sa.Column("supporting_run_ids", JSONB, nullable=False),
        sa.Column("confidence", sa.String(length=16), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("reviewed_by", sa.String(length=128), nullable=True),
        sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "status IN ('staging', 'approved', 'rejected')",
            name="skill_candidates_status_valid",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_skill_candidates_applies_to", "skill_candidates", ["applies_to"])
    op.create_index("ix_skill_candidates_status", "skill_candidates", ["status"])

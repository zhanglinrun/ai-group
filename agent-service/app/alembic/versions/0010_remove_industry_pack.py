"""remove industry_pack and add agent-native run context

Revision ID: 0010_remove_industry_pack
Revises: 0004_add_conclusions
Create Date: 2026-05-29 19:00:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0010_remove_industry_pack"
down_revision: str | None = "0004_add_conclusions"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("runs", sa.Column("domain_hint", sa.Text(), nullable=True))
    op.add_column(
        "runs",
        sa.Column(
            "reference_urls",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
            server_default=sa.text("'[]'::jsonb"),
        ),
    )
    op.drop_index(op.f("ix_runs_industry_pack"), table_name="runs")
    op.drop_column("runs", "industry_pack")

    op.add_column("skill_candidates", sa.Column("applies_to", sa.String(length=32), nullable=True))
    op.add_column(
        "skill_candidates",
        sa.Column(
            "tags",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
            server_default=sa.text("'[]'::jsonb"),
        ),
    )

    op.execute(
        """
        UPDATE skill_candidates
        SET applies_to = candidate_type
        WHERE applies_to IS NULL
        """
    )
    op.execute(
        """
        UPDATE skill_candidates
        SET tags = CASE
            WHEN industry_pack IS NULL OR btrim(industry_pack) = '' THEN '[]'::jsonb
            ELSE jsonb_build_array(industry_pack)
        END
        """
    )

    op.alter_column("skill_candidates", "applies_to", existing_type=sa.String(length=32), nullable=False)
    op.create_index(
        "ix_skill_candidates_tags_gin",
        "skill_candidates",
        ["tags"],
        unique=False,
        postgresql_using="gin",
    )
    op.create_index(
        "ix_skill_candidates_applies_to",
        "skill_candidates",
        ["applies_to"],
        unique=False,
    )
    op.drop_index(op.f("ix_skill_candidates_industry_pack"), table_name="skill_candidates")
    op.drop_column("skill_candidates", "industry_pack")


def downgrade() -> None:
    op.add_column("skill_candidates", sa.Column("industry_pack", sa.String(length=128), nullable=True))
    op.execute(
        """
        UPDATE skill_candidates
        SET industry_pack = COALESCE(tags ->> 0, 'generic')
        """
    )
    op.alter_column("skill_candidates", "industry_pack", existing_type=sa.String(length=128), nullable=False)
    op.create_index(
        op.f("ix_skill_candidates_industry_pack"),
        "skill_candidates",
        ["industry_pack"],
        unique=False,
    )
    op.drop_index("ix_skill_candidates_applies_to", table_name="skill_candidates")
    op.drop_index("ix_skill_candidates_tags_gin", table_name="skill_candidates")
    op.drop_column("skill_candidates", "tags")
    op.drop_column("skill_candidates", "applies_to")

    op.add_column("runs", sa.Column("industry_pack", sa.String(length=128), nullable=True))
    op.create_index(op.f("ix_runs_industry_pack"), "runs", ["industry_pack"], unique=False)
    op.drop_column("runs", "reference_urls")
    op.drop_column("runs", "domain_hint")

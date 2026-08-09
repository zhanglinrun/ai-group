"""add llm trace fields

Revision ID: 0016_add_llm_trace_fields
Revises: 0015_add_run_create_requests
Create Date: 2026-06-05 14:35:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0016_add_llm_trace_fields"
down_revision: str | None = "0015_add_run_create_requests"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("llm_calls", sa.Column("prompt_text", sa.Text(), nullable=True))
    op.add_column(
        "llm_calls",
        sa.Column(
            "response_content",
            postgresql.JSONB(astext_type=sa.Text(), none_as_null=True),
            nullable=True,
        ),
    )
    op.add_column("llm_calls", sa.Column("response_raw", sa.Text(), nullable=True))
    op.add_column("llm_calls", sa.Column("prompt_preview", sa.Text(), nullable=True))
    op.add_column("llm_calls", sa.Column("fallback_used", sa.Boolean(), nullable=True))
    op.add_column("llm_calls", sa.Column("fallback_reason", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("llm_calls", "fallback_reason")
    op.drop_column("llm_calls", "fallback_used")
    op.drop_column("llm_calls", "prompt_preview")
    op.drop_column("llm_calls", "response_raw")
    op.drop_column("llm_calls", "response_content")
    op.drop_column("llm_calls", "prompt_text")

"""persist per-call Member quota settlement state"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0028_llm_billing_attempts"
down_revision: str | None = "0027_remove_skill_evolution"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "llm_billing_attempts",
        sa.Column("attempt_id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("reservation_id", sa.String(length=64), nullable=False),
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("owner_user_id", sa.BigInteger(), nullable=False),
        sa.Column("estimated_micro_points", sa.BigInteger(), nullable=False),
        sa.Column("actual_micro_points", sa.BigInteger(), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("retry_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["run_id"], ["runs.run_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("attempt_id"),
        sa.UniqueConstraint("reservation_id", name="uq_llm_billing_attempts_reservation_id"),
        sa.UniqueConstraint("request_id", name="uq_llm_billing_attempts_request_id"),
    )
    op.create_index("ix_llm_billing_attempts_run_id", "llm_billing_attempts", ["run_id"])
    op.create_index("ix_llm_billing_attempts_owner_user_id", "llm_billing_attempts", ["owner_user_id"])
    op.create_index("ix_llm_billing_attempts_status", "llm_billing_attempts", ["status"])


def downgrade() -> None:
    op.drop_index("ix_llm_billing_attempts_status", table_name="llm_billing_attempts")
    op.drop_index("ix_llm_billing_attempts_owner_user_id", table_name="llm_billing_attempts")
    op.drop_index("ix_llm_billing_attempts_run_id", table_name="llm_billing_attempts")
    op.drop_table("llm_billing_attempts")

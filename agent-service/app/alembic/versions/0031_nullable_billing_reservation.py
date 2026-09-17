"""allow billing attempts without a Member freeze id"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0031_nullable_reservation"
down_revision: str | None = "0030_run_execution_lease"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column(
        "llm_billing_attempts",
        "reservation_id",
        existing_type=sa.String(length=64),
        nullable=True,
    )


def downgrade() -> None:
    op.alter_column(
        "llm_billing_attempts",
        "reservation_id",
        existing_type=sa.String(length=64),
        nullable=False,
    )

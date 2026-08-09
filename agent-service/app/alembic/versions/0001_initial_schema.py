"""initial persistence schema

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-05-23 18:30:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0001_initial_schema"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "runs",
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("user_query", sa.Text(), nullable=False),
        sa.Column("industry_pack", sa.String(length=128), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column(
            "target_roles",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "competitors",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("started_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("run_id", name=op.f("pk_runs")),
    )
    op.create_index(op.f("ix_runs_industry_pack"), "runs", ["industry_pack"], unique=False)
    op.create_index(op.f("ix_runs_status"), "runs", ["status"], unique=False)
    op.create_index("ix_runs_competitors_gin", "runs", ["competitors"], unique=False, postgresql_using="gin")
    op.create_index("ix_runs_target_roles_gin", "runs", ["target_roles"], unique=False, postgresql_using="gin")

    op.create_table(
        "steps",
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("agent_name", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("retry_count", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column(
            "payload",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("started_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["runs.run_id"], name=op.f("fk_steps_run_id_runs"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("step_id", name=op.f("pk_steps")),
    )
    op.create_index(op.f("ix_steps_run_id"), "steps", ["run_id"], unique=False)
    op.create_index(op.f("ix_steps_status"), "steps", ["status"], unique=False)
    op.create_index("ix_steps_payload_gin", "steps", ["payload"], unique=False, postgresql_using="gin")

    op.create_table(
        "llm_calls",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("model_slot", sa.String(length=64), nullable=False),
        sa.Column("model_name", sa.String(length=128), nullable=True),
        sa.Column("prompt_hash", sa.String(length=128), nullable=True),
        sa.Column("prompt_tokens", sa.Integer(), nullable=True),
        sa.Column("completion_tokens", sa.Integer(), nullable=True),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["step_id"],
            ["steps.step_id"],
            name=op.f("fk_llm_calls_step_id_steps"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_llm_calls")),
    )
    op.create_index(op.f("ix_llm_calls_prompt_hash"), "llm_calls", ["prompt_hash"], unique=False)
    op.create_index(op.f("ix_llm_calls_step_id"), "llm_calls", ["step_id"], unique=False)

    op.create_table(
        "supervisor_decisions",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("iteration", sa.Integer(), nullable=False),
        sa.Column("chosen_tool", sa.String(length=32), nullable=False),
        sa.Column(
            "tool_args",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("reasoning_summary", sa.Text(), nullable=False),
        sa.Column("triggered_by", sa.String(length=64), nullable=True),
        sa.Column("outcome", sa.String(length=32), nullable=True),
        sa.Column("outcome_recorded_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["run_id"],
            ["runs.run_id"],
            name=op.f("fk_supervisor_decisions_run_id_runs"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_supervisor_decisions")),
    )
    op.create_index(op.f("ix_supervisor_decisions_run_id"), "supervisor_decisions", ["run_id"], unique=False)
    op.create_index(
        "ix_supervisor_decisions_tool_args_gin",
        "supervisor_decisions",
        ["tool_args"],
        unique=False,
        postgresql_using="gin",
    )

    op.create_table(
        "evidence",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("source_type", sa.String(length=64), nullable=False),
        sa.Column("source_url", sa.Text(), nullable=True),
        sa.Column("source_title", sa.Text(), nullable=True),
        sa.Column("quote", sa.Text(), nullable=False),
        sa.Column("sanitized_text", sa.Text(), nullable=False),
        sa.Column("span", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("collected_by", sa.String(length=64), nullable=False),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("desensitized", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["runs.run_id"], name=op.f("fk_evidence_run_id_runs"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_evidence")),
    )
    op.create_index(op.f("ix_evidence_run_id"), "evidence", ["run_id"], unique=False)
    op.create_index("ix_evidence_span_gin", "evidence", ["span"], unique=False, postgresql_using="gin")

    op.create_table(
        "reports",
        sa.Column("report_id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column(
            "content_json",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("content_markdown", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["runs.run_id"], name=op.f("fk_reports_run_id_runs"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("report_id", name=op.f("pk_reports")),
    )
    op.create_index(op.f("ix_reports_run_id"), "reports", ["run_id"], unique=False)
    op.create_index(op.f("ix_reports_status"), "reports", ["status"], unique=False)
    op.create_index("ix_reports_content_json_gin", "reports", ["content_json"], unique=False, postgresql_using="gin")

    op.create_table(
        "artifacts",
        sa.Column("artifact_id", sa.String(length=64), nullable=False),
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("kind", sa.String(length=64), nullable=False),
        sa.Column("uri", sa.Text(), nullable=False),
        sa.Column("sha256", sa.String(length=128), nullable=True),
        sa.Column("size_bytes", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["step_id"],
            ["steps.step_id"],
            name=op.f("fk_artifacts_step_id_steps"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("artifact_id", name=op.f("pk_artifacts")),
    )
    op.create_index(op.f("ix_artifacts_step_id"), "artifacts", ["step_id"], unique=False)

    op.create_table(
        "skill_candidates",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("candidate_type", sa.String(length=32), nullable=False),
        sa.Column("industry_pack", sa.String(length=128), nullable=False),
        sa.Column(
            "payload",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("rationale", sa.Text(), nullable=False),
        sa.Column(
            "supporting_run_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("confidence", sa.String(length=16), nullable=False),
        sa.Column("status", sa.String(length=16), server_default=sa.text("'staging'"), nullable=False),
        sa.Column("reviewed_by", sa.String(length=128), nullable=True),
        sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint(
            "status IN ('staging', 'approved', 'rejected')",
            name=op.f("ck_skill_candidates_skill_candidates_status_valid"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_skill_candidates")),
    )
    op.create_index(op.f("ix_skill_candidates_industry_pack"), "skill_candidates", ["industry_pack"], unique=False)
    op.create_index(op.f("ix_skill_candidates_status"), "skill_candidates", ["status"], unique=False)
    op.create_index(
        "ix_skill_candidates_payload_gin",
        "skill_candidates",
        ["payload"],
        unique=False,
        postgresql_using="gin",
    )
    op.create_index(
        "ix_skill_candidates_supporting_runs_gin",
        "skill_candidates",
        ["supporting_run_ids"],
        unique=False,
        postgresql_using="gin",
    )


def downgrade() -> None:
    op.drop_table("skill_candidates")
    op.drop_table("artifacts")
    op.drop_table("reports")
    op.drop_table("evidence")
    op.drop_table("supervisor_decisions")
    op.drop_table("llm_calls")
    op.drop_table("steps")
    op.drop_table("runs")

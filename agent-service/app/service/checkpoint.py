from __future__ import annotations

import json
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from schemas.intake import (
    IntakeClarifyRequest,
    IntakeExchange,
    IntakeUserReply,
    RunIntakeDraft,
)
from schemas.plan import FollowUpRequest, PlanTask, PlanTree
from schemas.supervisor import (
    Analyze,
    ConductResearch,
    ConductResearchBatch,
    DiscoverCompetitors,
    Finalize,
    SupervisorDecision,
    Write,
)


CHECKPOINT_ALLOWED_TYPES = (
    RunIntakeDraft,
    IntakeClarifyRequest,
    IntakeUserReply,
    IntakeExchange,
    PlanTask,
    PlanTree,
    FollowUpRequest,
    DiscoverCompetitors,
    ConductResearch,
    ConductResearchBatch,
    Analyze,
    Write,
    Finalize,
    SupervisorDecision,
)


class PostgresCheckpointSerializer(JsonPlusSerializer):
    """Bridge the Postgres metadata JSON API removed in checkpoint 4.x."""

    def dumps(self, obj: Any) -> bytes:
        return json.dumps(
            obj,
            ensure_ascii=False,
            separators=(",", ":"),
            check_circular=True,
        ).encode("utf-8")

    def loads(self, data: bytes | str) -> Any:
        if isinstance(data, bytes):
            data = data.decode("utf-8")
        return json.loads(data)


def checkpoint_serializer() -> PostgresCheckpointSerializer:
    return PostgresCheckpointSerializer(allowed_msgpack_modules=CHECKPOINT_ALLOWED_TYPES)


@asynccontextmanager
async def postgres_checkpointer(dsn: str) -> AsyncIterator[AsyncPostgresSaver]:
    serializer = checkpoint_serializer()
    async with AsyncPostgresSaver.from_conn_string(dsn, serde=serializer) as checkpointer:
        # checkpoint-postgres still uses a separate legacy serializer for its JSONB
        # metadata column. Install the same restricted adapter on that path as well.
        checkpointer.jsonplus_serde = serializer
        yield checkpointer

"""Safe defaults for isolated unit tests.

The production settings intentionally fail fast when a real provider or Postgres
DSN is missing. Unit tests exercise pure application code and must not require a
developer's .env or a running external service.
"""

from __future__ import annotations

import asyncio
from collections.abc import Generator
import os
import sys

import pytest
from fastapi.testclient import TestClient


_DEFAULTS = {
    "DATABASE_URL": "postgresql+asyncpg://test:test@localhost/xiongdoctor",
    "DATABASE_URL_SYNC": "postgresql+psycopg2://test:test@localhost/xiongdoctor",
    "LANGGRAPH_CHECKPOINT_DSN": "postgresql://test:test@localhost/xiongdoctor",
    "LLM_ACTIVE_PROVIDER": "openai",
    "OPENAI_API_KEY": "unit-test-key",
    "OPENAI_DEFAULT_MODEL": "gpt-4o-mini",
    "OPENAI_MODEL_BALANCED": "gpt-4o-mini",
    "ALLOW_ANONYMOUS_DEV": "true",
}

for _name, _value in _DEFAULTS.items():
    os.environ.setdefault(_name, _value)

if sys.platform == "win32":
    # psycopg's async driver cannot run on the Windows Proactor loop.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


@pytest.fixture()
def test_client() -> Generator[TestClient, None, None]:
    """Use the real FastAPI lifespan for API tests.

    The full API suite intentionally needs the Postgres/ checkpoint services
    from the development Compose stack; pure unit tests do not request this
    fixture and remain offline.
    """

    from app_main import app

    with TestClient(app) as client:
        yield client

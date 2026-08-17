from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
import jwt
import pytest

from core.config import settings
from security.identity import IdentityContext, require_identity

SECRET = "unit-test-identity-signing-secret!"
ISSUER = "ai-group-gateway"
AUDIENCE = "ai-group-internal"


def _signing_key(secret: str) -> bytes:
    return hashlib.sha256(secret.encode("utf-8")).digest()


def _mint(
    *,
    user_id: int = 42,
    username: str = "alice",
    role: str = "USER",
    secret: str = SECRET,
    audience: str = AUDIENCE,
    expires_delta: timedelta = timedelta(seconds=60),
) -> str:
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "username": username,
        "role": role,
        "iss": ISSUER,
        "aud": audience,
        "iat": now,
        "exp": now + expires_delta,
        "jti": "test-jti",
    }
    return jwt.encode(payload, _signing_key(secret), algorithm="HS256")


@pytest.fixture()
def identity_client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    monkeypatch.setattr(settings, "INTERNAL_TOKEN", "internal-token")
    monkeypatch.setattr(settings, "IDENTITY_SIGNING_SECRET", SECRET)
    monkeypatch.setattr(settings, "IDENTITY_JWT_ISSUER", ISSUER)
    monkeypatch.setattr(settings, "IDENTITY_JWT_AUDIENCE", AUDIENCE)
    monkeypatch.setattr(settings, "ALLOW_ANONYMOUS_DEV", False)

    app = FastAPI()

    @app.get("/whoami")
    async def whoami(identity: IdentityContext = Depends(require_identity)) -> dict[str, object]:
        return {"user_id": identity.user_id, "username": identity.username, "role": identity.role}

    return TestClient(app)


def test_valid_jwt_binds_claims(identity_client: TestClient) -> None:
    token = _mint()
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "internal-token", "X-Internal-Jwt": token},
    )
    assert response.status_code == 200
    assert response.json() == {"user_id": 42, "username": "alice", "role": "USER"}


def test_expired_jwt_is_rejected(identity_client: TestClient) -> None:
    token = _mint(expires_delta=timedelta(seconds=-60))
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "internal-token", "X-Internal-Jwt": token},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid identity signature"


def test_wrong_secret_is_rejected(identity_client: TestClient) -> None:
    token = _mint(secret="other-identity-signing-secret-32b!")
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "internal-token", "X-Internal-Jwt": token},
    )
    assert response.status_code == 401


def test_wrong_audience_is_rejected(identity_client: TestClient) -> None:
    token = _mint(audience="other-audience")
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "internal-token", "X-Internal-Jwt": token},
    )
    assert response.status_code == 401


def test_internal_token_mismatch_is_rejected(identity_client: TestClient) -> None:
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "wrong-token", "X-Internal-Jwt": _mint()},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid internal service credential"


def test_missing_jwt_is_rejected_when_anonymous_disabled(identity_client: TestClient) -> None:
    response = identity_client.get(
        "/whoami",
        headers={"X-Internal-Token": "internal-token"},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "gateway identity is required"


def test_anonymous_dev_allows_missing_jwt(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "INTERNAL_TOKEN", None)
    monkeypatch.setattr(settings, "IDENTITY_SIGNING_SECRET", None)
    monkeypatch.setattr(settings, "ALLOW_ANONYMOUS_DEV", True)

    app = FastAPI()

    @app.get("/whoami")
    async def whoami(identity: IdentityContext = Depends(require_identity)) -> dict[str, object]:
        return {"user_id": identity.user_id, "username": identity.username, "role": identity.role}

    response = TestClient(app).get("/whoami")
    assert response.status_code == 200
    assert response.json() == {"user_id": 0, "username": "local-dev", "role": "USER"}

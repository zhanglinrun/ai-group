from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
from secrets import compare_digest
import hashlib
import re

import jwt
from fastapi import Header, HTTPException, Request
from jwt import InvalidTokenError

from core.config import settings


# ``/api/runs/intake`` and the maintenance endpoints are collection routes,
# not run-detail routes.  Treating ``intake`` as a run id caused every new
# analysis request to fail with a misleading 404 before the intake handler ran.
_RUN_PATH = re.compile(
    r"^/api/runs/(?!intake(?:/|$)|batch-delete(?:/|$)|clear(?:/|$))([^/]+)(?:/|$)"
)


@dataclass(frozen=True, slots=True)
class IdentityContext:
    user_id: int
    username: str | None = None
    role: str = "USER"


_identity_ctx: ContextVar[IdentityContext | None] = ContextVar("xiongdoctor_identity", default=None)


def _jwt_secret() -> str:
    return (settings.IDENTITY_SIGNING_SECRET or "").strip()


def _signing_key(secret: str) -> bytes:
    return hashlib.sha256(secret.encode("utf-8")).digest()


def _decode_identity_jwt(token: str) -> IdentityContext:
    secret = _jwt_secret()
    if not secret:
        raise HTTPException(status_code=401, detail="invalid identity signature")
    try:
        payload = jwt.decode(
            token,
            _signing_key(secret),
            algorithms=["HS256"],
            audience=settings.IDENTITY_JWT_AUDIENCE,
            issuer=settings.IDENTITY_JWT_ISSUER,
        )
    except InvalidTokenError as exc:
        raise HTTPException(status_code=401, detail="invalid identity signature") from exc
    subject = payload.get("sub")
    try:
        user_id = int(subject)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="invalid user id") from exc
    role = str(payload.get("role") or "USER").upper()
    username = payload.get("username")
    return IdentityContext(
        user_id=user_id,
        username=str(username) if username is not None else None,
        role=role,
    )


async def require_identity(
    request: Request,
    x_internal_token: str | None = Header(default=None),
    x_internal_jwt: str | None = Header(default=None),
) -> IdentityContext:
    """Validate the Gateway HS256 identity JWT.

    Development-only anonymous mode keeps local unit tests and the isolated
    Agent demo usable. The setting defaults to false; Compose/production keep
    it false and require a non-empty internal token/signing secret.
    """
    configured_internal = (settings.INTERNAL_TOKEN or "").strip()
    if configured_internal and not compare_digest(configured_internal, x_internal_token or ""):
        raise HTTPException(status_code=401, detail="invalid internal service credential")

    if not x_internal_jwt:
        if settings.ALLOW_ANONYMOUS_DEV:
            identity = IdentityContext(user_id=0, username="local-dev", role="USER")
            _identity_ctx.set(identity)
            return identity
        raise HTTPException(status_code=401, detail="gateway identity is required")

    identity = _decode_identity_jwt(x_internal_jwt)
    _identity_ctx.set(identity)
    await _assert_run_owner(request, identity)
    return identity


async def _assert_run_owner(request: Request, identity: IdentityContext) -> None:
    if identity.user_id == 0:
        return
    matched = _RUN_PATH.match(request.url.path)
    if matched is None:
        return
    from db.engine import get_session_factory  # noqa: PLC0415
    from models.run import Run  # noqa: PLC0415

    async with get_session_factory()() as session:
        run = await session.get(Run, matched.group(1))
    if run is None or int(run.owner_user_id or 0) != identity.user_id:
        raise HTTPException(status_code=404, detail="run not found")


def get_identity() -> IdentityContext:
    identity = _identity_ctx.get()
    if identity is None:
        if settings.ALLOW_ANONYMOUS_DEV:
            return IdentityContext(user_id=0, username="local-dev", role="USER")
        raise HTTPException(status_code=401, detail="identity is not initialized")
    return identity

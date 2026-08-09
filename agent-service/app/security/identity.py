from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
import hashlib
import hmac
import re
import time

from fastapi import Header, HTTPException, Request

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


def _signed_payload(request: Request, *, user_id: str, role: str, timestamp: str, nonce: str) -> str:
    # The Gateway signs the browser-facing route, while the BFF rewrites it to
    # the internal Agent route. Bind the envelope to the verified identity and
    # freshness, not to a path that legitimately changes at a proxy boundary.
    return ".".join((user_id, role, timestamp, nonce))


def _verify_signature(
    request: Request,
    *,
    user_id: str,
    role: str,
    timestamp: str | None,
    nonce: str | None,
    signature: str | None,
) -> bool:
    secret = (settings.IDENTITY_SIGNING_SECRET or "").strip()
    if not secret:
        return False
    if not timestamp or not nonce or not signature:
        return False
    try:
        timestamp_number = int(timestamp)
    except ValueError:
        return False
    if abs(int(time.time()) - timestamp_number) > settings.IDENTITY_MAX_AGE_SECONDS:
        return False
    expected = hmac.new(
        secret.encode("utf-8"),
        _signed_payload(request, user_id=user_id, role=role, timestamp=timestamp, nonce=nonce).encode(
            "utf-8"
        ),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


async def require_identity(
    request: Request,
    x_user_id: str | None = Header(default=None),
    x_username: str | None = Header(default=None),
    x_role: str | None = Header(default=None),
    x_internal_token: str | None = Header(default=None),
    x_auth_timestamp: str | None = Header(default=None),
    x_auth_nonce: str | None = Header(default=None),
    x_auth_signature: str | None = Header(default=None),
) -> IdentityContext:
    """Validate the Gateway identity envelope.

    Development-only anonymous mode keeps local unit tests and the isolated
    Agent demo usable. Compose/production sets ALLOW_ANONYMOUS_DEV=false and a
    non-empty internal token/signing secret.
    """
    configured_internal = (settings.INTERNAL_TOKEN or "").strip()
    if configured_internal and not hmac.compare_digest(configured_internal, x_internal_token or ""):
        raise HTTPException(status_code=401, detail="invalid internal service credential")

    if x_user_id is None:
        if settings.ALLOW_ANONYMOUS_DEV:
            identity = IdentityContext(user_id=0, username="local-dev", role="USER")
            _identity_ctx.set(identity)
            return identity
        raise HTTPException(status_code=401, detail="gateway identity is required")

    try:
        user_id = int(x_user_id)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="invalid user id") from exc
    role = (x_role or "USER").upper()
    if settings.IDENTITY_SIGNING_SECRET and not _verify_signature(
        request,
        user_id=x_user_id,
        role=role,
        timestamp=x_auth_timestamp,
        nonce=x_auth_nonce,
        signature=x_auth_signature,
    ):
        raise HTTPException(status_code=401, detail="invalid identity signature")
    identity = IdentityContext(user_id=user_id, username=x_username, role=role)
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

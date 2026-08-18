from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import uuid4

import httpx

from core.config import settings
from security.identity import current_internal_jwt
from utils.logger import get_logger

log = get_logger("service.billing")


@dataclass(frozen=True, slots=True)
class Reservation:
    reservation_id: str
    amount_micro_points: int
    request_id: str
    user_id: int


def charge_micro_points(prompt_tokens: int | None, completion_tokens: int | None) -> int:
    """Calculate the exact usage charge from the provider-reported token counts.

    Token usage is settled at token granularity.  We intentionally do not round
    each call up to a 1K-token block: doing that would charge a one-token call as
    a full block and would make the displayed ledger diverge from actual usage.
    """
    prompt = max(0, int(prompt_tokens or 0))
    completion = max(0, int(completion_tokens or 0))
    return (
        prompt * settings.BILLING_INPUT_MICRO_POINTS_PER_TOKEN
        + completion * settings.BILLING_OUTPUT_MICRO_POINTS_PER_TOKEN
    )


class MemberQuotaClient:
    def __init__(self) -> None:
        self.base_url = settings.MEMBER_SERVICE_URL.rstrip("/")
        self.timeout = httpx.Timeout(8.0, connect=2.0)

    def _headers(self) -> dict[str, str]:
        headers = {"X-Internal-Token": settings.INTERNAL_TOKEN or ""}
        jwt = current_internal_jwt()
        if jwt:
            headers["X-Internal-Jwt"] = jwt
        return headers

    async def reserve(self, *, user_id: int, amount_micro_points: int, run_id: str, trace_id: str) -> Reservation:
        request_id = f"agent:{run_id}"
        if user_id == 0 or not settings.INTERNAL_TOKEN:
            return Reservation(run_id, amount_micro_points, request_id, user_id)
        payload = {
            "userId": user_id,
            "amount": amount_micro_points,
            # Member's reservation contract requires a positive lower bound.
            # Agent reservations represent the run budget, so a partial freeze
            # would make an under-funded run appear billable. Require the full
            # requested budget and let the caller surface a quota error instead.
            "minAmount": amount_micro_points,
            "abilityCode": "llm",
            "requestId": request_id,
            "traceId": trace_id,
            "ownerService": "agent-service",
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/internal/member/quota/reservations",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()
            body = response.json()
        data = body.get("data") or {}
        freeze_id = str(data.get("freezeId") or data.get("freeze_id") or "")
        if not freeze_id:
            raise RuntimeError(f"member reservation failed: {body}")
        return Reservation(freeze_id, int(data.get("amount") or amount_micro_points), request_id, user_id)

    async def confirm(self, reservation: Reservation, *, actual_micro_points: int, trace_id: str) -> None:
        if reservation.user_id == 0 or not settings.INTERNAL_TOKEN:
            return
        payload = {
            "freezeId": reservation.reservation_id,
            # Never silently clip real token usage to the reservation.  If the
            # run exceeded its pre-reserved budget, Member must reject the
            # confirmation so the run enters reconciliation instead of being
            # marked settled with an undercharge.
            "actualAmount": max(0, int(actual_micro_points)),
            "requestId": reservation.request_id,
            "traceId": trace_id,
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/internal/member/quota/reservations/{reservation.reservation_id}/confirm",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()

    async def release(self, reservation: Reservation, *, trace_id: str) -> None:
        if reservation.user_id == 0 or not settings.INTERNAL_TOKEN:
            return
        payload = {"freezeId": reservation.reservation_id, "requestId": reservation.request_id, "traceId": trace_id}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/internal/member/quota/reservations/{reservation.reservation_id}/release",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()


quota_client = MemberQuotaClient()


def default_reservation_amount(requested: int | None) -> int:
    value = requested if requested is not None else settings.BILLING_DEFAULT_RESERVATION_MICRO_POINTS
    return max(1, min(int(value), 100_000_000))

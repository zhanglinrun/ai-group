from __future__ import annotations

from dataclasses import dataclass

import httpx

from core.config import settings
from core.nacos_discovery import lookup_member_base_url
from core.tiers import normalize_analysis_tier
from security.identity import current_internal_jwt, mint_current_identity_jwt
from utils.logger import get_logger

log = get_logger("service.billing")


@dataclass(frozen=True, slots=True)
class Reservation:
    reservation_id: str
    amount_micro_points: int
    request_id: str
    user_id: int


@dataclass(frozen=True, slots=True)
class FreezeSlice:
    freeze_id: str
    request_id: str
    amount_micro_points: int

    def to_record(self) -> dict[str, object]:
        return {
            "freeze_id": self.freeze_id,
            "request_id": self.request_id,
            "amount": self.amount_micro_points,
        }


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


def clamp_reservation_amount(value: int) -> int:
    return max(1, min(int(value), settings.BILLING_MAX_RESERVATION_MICRO_POINTS))


def reservation_amount_for_tier(report_depth: str | None) -> int:
    tier = normalize_analysis_tier(report_depth)
    amounts = {
        "debug": settings.BILLING_RESERVATION_MICRO_POINTS_DEBUG,
        "quick": settings.BILLING_RESERVATION_MICRO_POINTS_QUICK,
        "deep": settings.BILLING_RESERVATION_MICRO_POINTS_DEEP,
    }
    return clamp_reservation_amount(amounts[tier])


def resolve_reservation_amount(
    *,
    report_depth: str | None = None,
    requested: int | None = None,
) -> int:
    if requested is not None:
        return clamp_reservation_amount(requested)
    if report_depth is not None:
        return reservation_amount_for_tier(report_depth)
    return clamp_reservation_amount(settings.BILLING_DEFAULT_RESERVATION_MICRO_POINTS)


def reservation_request_id(run_id: str, kind: str = "primary") -> str:
    """Idempotency key for leftover settlement slices.

    Live calls use `agent:{run_id}:call:{uuid}` in billing_meter.
    """
    if kind == "primary":
        return f"agent:{run_id}"
    return f"agent:{run_id}:{kind}"


def freeze_slices_from_mapping(
    *,
    run_id: str,
    reservation_id: str | None,
    reserved_micro_points: int,
    billing_reservations: object,
) -> list[FreezeSlice]:
    slices: list[FreezeSlice] = []
    if isinstance(billing_reservations, list):
        for item in billing_reservations:
            if not isinstance(item, dict):
                continue
            freeze_id = str(item.get("freeze_id") or "")
            request_id = str(item.get("request_id") or "")
            amount_raw = item.get("amount")
            try:
                amount = int(amount_raw)
            except (TypeError, ValueError):
                continue
            if not freeze_id or amount <= 0:
                continue
            slices.append(
                FreezeSlice(
                    freeze_id=freeze_id,
                    request_id=request_id or reservation_request_id(run_id),
                    amount_micro_points=amount,
                )
            )
    if slices:
        return slices
    if reservation_id:
        return [
            FreezeSlice(
                freeze_id=reservation_id,
                request_id=reservation_request_id(run_id),
                amount_micro_points=max(0, int(reserved_micro_points or 0)),
            )
        ]
    return []


def allocate_actual_across_slices(
    slices: list[FreezeSlice],
    actual_micro_points: int,
) -> tuple[list[tuple[FreezeSlice, int]], int]:
    remaining = max(0, int(actual_micro_points))
    assignments: list[tuple[FreezeSlice, int]] = []
    for slice_ in slices:
        take = min(remaining, max(0, slice_.amount_micro_points))
        assignments.append((slice_, take))
        remaining -= take
    return assignments, remaining


class QuotaExhaustedError(Exception):
    """Raised when Member has no remaining quota for the next model call."""


MEMBER_SUCCESS_CODE = 200
MEMBER_QUOTA_INSUFFICIENT_CODE = 621


def _member_code(body: dict[str, object]) -> int | None:
    raw = body.get("code")
    if raw is None:
        return None
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


def _member_data(body: dict[str, object], *, context: str) -> dict[str, object]:
    code = _member_code(body)
    if code not in {None, MEMBER_SUCCESS_CODE}:
        message = str(body.get("message") or context)
        if code == MEMBER_QUOTA_INSUFFICIENT_CODE:
            raise QuotaExhaustedError(message)
        raise RuntimeError(f"{context}: {message}")
    data = body.get("data") or {}
    if not isinstance(data, dict):
        return {}
    return data


class MemberQuotaClient:
    def __init__(self) -> None:
        self.timeout = httpx.Timeout(8.0, connect=2.0)

    def _base_url(self) -> str:
        return lookup_member_base_url(settings).rstrip("/")

    def _headers(self) -> dict[str, str]:
        headers = {"X-Internal-Token": settings.INTERNAL_TOKEN or ""}
        # A Gateway JWT expires after 60 seconds, while a deep run may last
        # many minutes. Refresh from the verified identity context for each
        # Member call so long-running billing requests are not rejected with
        # 401 after the original request token expires.
        jwt = mint_current_identity_jwt() or current_internal_jwt()
        if jwt:
            headers["X-Internal-Jwt"] = jwt
        return headers

    async def reserve(
        self,
        *,
        user_id: int,
        amount_micro_points: int,
        run_id: str,
        trace_id: str,
        request_id: str | None = None,
    ) -> Reservation:
        resolved_request_id = request_id or reservation_request_id(run_id)
        if user_id == 0 or not settings.INTERNAL_TOKEN:
            return Reservation(run_id, amount_micro_points, resolved_request_id, user_id)
        payload = {
            "userId": user_id,
            "amount": amount_micro_points,
            # Per-call authorization: require the full estimated amount. A partial
            # hold would let an under-funded call look billable.
            "minAmount": amount_micro_points,
            "abilityCode": "llm",
            "requestId": resolved_request_id,
            "traceId": trace_id,
            "ownerService": "agent-service",
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self._base_url()}/internal/member/quota/reservations",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()
            body = response.json()
        if not isinstance(body, dict):
            raise RuntimeError(f"member reservation failed: {body}")
        data = _member_data(body, context="member reservation failed")
        freeze_id = str(data.get("freezeId") or data.get("freeze_id") or "")
        if not freeze_id:
            raise RuntimeError(f"member reservation failed: {body}")
        return Reservation(freeze_id, int(data.get("amount") or amount_micro_points), resolved_request_id, user_id)

    async def available_micro_points(self, *, user_id: int) -> int | None:
        if user_id == 0 or not settings.INTERNAL_TOKEN:
            return None
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(
                f"{self._base_url()}/internal/member/quota/{user_id}",
                headers=self._headers(),
            )
            response.raise_for_status()
            body = response.json()
        if not isinstance(body, dict):
            return None
        data = _member_data(body, context="member quota lookup failed")
        raw = data.get("availableQuota")
        if raw is None:
            return None
        return max(0, int(raw))

    async def confirm(self, reservation: Reservation, *, actual_micro_points: int, trace_id: str) -> None:
        if reservation.user_id == 0 or not settings.INTERNAL_TOKEN:
            return
        payload = {
            "freezeId": reservation.reservation_id,
            "actualAmount": max(0, int(actual_micro_points)),
            "requestId": reservation.request_id,
            "traceId": trace_id,
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self._base_url()}/internal/member/quota/reservations/{reservation.reservation_id}/confirm",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()
            body = response.json()
        if isinstance(body, dict):
            _member_data(body, context="member confirm failed")

    async def release(self, reservation: Reservation, *, trace_id: str) -> None:
        if reservation.user_id == 0 or not settings.INTERNAL_TOKEN:
            return
        payload = {"freezeId": reservation.reservation_id, "requestId": reservation.request_id, "traceId": trace_id}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self._base_url()}/internal/member/quota/reservations/{reservation.reservation_id}/release",
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()
            body = response.json()
        if isinstance(body, dict):
            _member_data(body, context="member release failed")


quota_client = MemberQuotaClient()

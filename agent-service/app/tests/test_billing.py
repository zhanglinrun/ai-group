from __future__ import annotations

from service.billing import MemberQuotaClient, charge_micro_points, default_reservation_amount
from security.identity import bind_internal_jwt


def test_charge_uses_exact_input_and_output_token_rates() -> None:
    # Input is 5 microcredits/token and output is 30 microcredits/token.
    # These are equivalent to 5 and 30 credits per million tokens.
    assert charge_micro_points(1, 1001) == 5 + (1001 * 30)


def test_missing_usage_is_not_charged() -> None:
    assert charge_micro_points(None, None) == 0
    assert charge_micro_points(0, 0) == 0


def test_reservation_amount_is_bounded() -> None:
    assert default_reservation_amount(None) > 0
    assert default_reservation_amount(2_000_000_000) == 100_000_000


def test_member_headers_forward_verified_internal_jwt(monkeypatch) -> None:
    monkeypatch.setattr("service.billing.settings.INTERNAL_TOKEN", "internal-token")
    bind_internal_jwt("signed-internal-jwt")
    try:
        headers = MemberQuotaClient()._headers()
        assert headers["X-Internal-Token"] == "internal-token"
        assert headers["X-Internal-Jwt"] == "signed-internal-jwt"
    finally:
        bind_internal_jwt(None)


def test_member_headers_stay_token_only_without_jwt(monkeypatch) -> None:
    monkeypatch.setattr("service.billing.settings.INTERNAL_TOKEN", "internal-token")
    bind_internal_jwt(None)
    headers = MemberQuotaClient()._headers()
    assert headers == {"X-Internal-Token": "internal-token"}

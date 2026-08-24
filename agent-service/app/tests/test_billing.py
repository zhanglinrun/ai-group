from __future__ import annotations

from service.billing import (
    MemberQuotaClient,
    QuotaExhaustedError,
    allocate_actual_across_slices,
    charge_micro_points,
    default_reservation_amount,
    freeze_slices_from_mapping,
    reservation_amount_for_tier,
    resolve_reservation_amount,
    _member_data,
)
from security.identity import bind_internal_jwt


def test_charge_uses_exact_input_and_output_token_rates() -> None:
    # Input is 5 microcredits/token and output is 30 microcredits/token.
    # These are equivalent to 5 and 30 credits per million tokens.
    assert charge_micro_points(1, 1001) == 5 + (1001 * 30)


def test_missing_usage_is_not_charged() -> None:
    assert charge_micro_points(None, None) == 0
    assert charge_micro_points(0, 0) == 0


def test_reservation_amount_is_bounded() -> None:
    assert default_reservation_amount(None) == 1_000_000
    assert default_reservation_amount(2_000_000_000) == 100_000_000


def test_reservation_amount_follows_report_depth() -> None:
    assert reservation_amount_for_tier("debug") == 300_000
    assert reservation_amount_for_tier("quick") == 1_000_000
    assert reservation_amount_for_tier("deep") == 2_000_000
    assert resolve_reservation_amount(report_depth="quick", requested=50_000) == 50_000
    assert resolve_reservation_amount(report_depth="deep") == 2_000_000


def test_allocate_actual_across_slices_leaves_overage() -> None:
    from service.billing import FreezeSlice

    slices = [
        FreezeSlice("frz_1", "agent:run_1", 1_000_000),
        FreezeSlice("frz_2", "agent:run_1:topup", 1_000_000),
    ]
    assignments, overage = allocate_actual_across_slices(slices, 2_300_000)
    assert [charged for _, charged in assignments] == [1_000_000, 1_000_000]
    assert overage == 300_000


def test_legacy_run_without_slice_list_uses_reservation_id() -> None:
    slices = freeze_slices_from_mapping(
        run_id="run_1",
        reservation_id="frz_1",
        reserved_micro_points=5000,
        billing_reservations=None,
    )
    assert len(slices) == 1
    assert slices[0].freeze_id == "frz_1"
    assert slices[0].amount_micro_points == 5000


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


def test_member_quota_insufficient_code_raises() -> None:
    try:
        _member_data({"code": 621, "message": "积分不足"}, context="member reservation failed")
    except QuotaExhaustedError as exc:
        assert "积分不足" in str(exc)
    else:
        raise AssertionError("expected QuotaExhaustedError")


def test_member_success_code_returns_data() -> None:
    data = _member_data({"code": 200, "data": {"freezeId": "frz_1"}}, context="x")
    assert data["freezeId"] == "frz_1"

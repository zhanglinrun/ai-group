from __future__ import annotations

from service.billing import charge_micro_points, default_reservation_amount


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

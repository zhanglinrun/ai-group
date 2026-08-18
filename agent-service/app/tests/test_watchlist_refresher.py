from service.watchlist.refresher import resolve_refresh_owner_user_id


def test_resolve_refresh_owner_prefers_request_identity() -> None:
    assert (
        resolve_refresh_owner_user_id(
            preferred_owner_user_id=8,
            last_run_owner_user_id=0,
            added_from_run_owner_user_id=3,
        )
        == 8
    )


def test_resolve_refresh_owner_inherits_from_previous_run() -> None:
    assert (
        resolve_refresh_owner_user_id(
            preferred_owner_user_id=0,
            last_run_owner_user_id=8,
            added_from_run_owner_user_id=3,
        )
        == 8
    )


def test_resolve_refresh_owner_falls_back_to_source_run() -> None:
    assert (
        resolve_refresh_owner_user_id(
            preferred_owner_user_id=None,
            last_run_owner_user_id=0,
            added_from_run_owner_user_id=8,
        )
        == 8
    )


def test_resolve_refresh_owner_stays_anonymous_without_account() -> None:
    assert (
        resolve_refresh_owner_user_id(
            preferred_owner_user_id=0,
            last_run_owner_user_id=None,
            added_from_run_owner_user_id=None,
        )
        == 0
    )

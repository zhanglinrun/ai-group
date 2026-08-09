from __future__ import annotations

import pytest

from service.desensitize import desensitize_text


def test_desensitize_text_strips_null_bytes() -> None:
    raw = "通义灵码\x00产品介绍"
    sanitized = desensitize_text(raw)
    assert "\x00" not in sanitized
    assert "通义灵码产品介绍" in sanitized


@pytest.mark.parametrize(
    ("raw_text", "expected_fragment", "forbidden_fragment"),
    [
        ("contact me at alice@example.com", "[REDACTED_EMAIL]", "alice@example.com"),
        ("手机号是13800138000", "[REDACTED_PHONE]", "13800138000"),
        ("+86 13800138000 is reachable", "[REDACTED_PHONE]", "+86 13800138000"),
        ("call 138 0013 8000 now", "[REDACTED_PHONE]", "138 0013 8000"),
        ("证件号 11010519491231002X", "[REDACTED_IDCARD]", "11010519491231002X"),
        ("id: 11010519491231002x", "[REDACTED_IDCARD]", "11010519491231002x"),
        ("thanks @john_doe for feedback", "@REDACTED_USER", "@john_doe"),
        ("感谢 @张三同学 提供线索", "@REDACTED_USER", "@张三同学"),
        (
            "avatar https://cdn.example.com/avatar/u123.png should hide",
            "[REDACTED_AVATAR_URL]",
            "https://cdn.example.com/avatar/u123.png",
        ),
        ("Authorization: Bearer abcDEF123._-==", "Bearer [REDACTED_TOKEN]", "Bearer abcDEF123._-=="),
        (
            "mail bob@example.com and token Bearer token123 then ping @ops",
            "Bearer [REDACTED_TOKEN]",
            "bob@example.com",
        ),
        ("No sensitive data here.", "No sensitive data here.", ""),
        (
            "头像链接 https://img.a.com/profile/abc.jpg?x=1",
            "[REDACTED_AVATAR_URL]",
            "https://img.a.com/profile/abc.jpg?x=1",
        ),
        ("mobile 138-0013-8000 for callback", "[REDACTED_PHONE]", "138-0013-8000"),
        (
            '{"email":"qa@demo.io","auth":"Bearer zz.y"}',
            "Bearer [REDACTED_TOKEN]",
            "qa@demo.io",
        ),
    ],
)
def test_desensitize_text_cases(
    raw_text: str,
    expected_fragment: str,
    forbidden_fragment: str,
) -> None:
    sanitized = desensitize_text(raw_text)
    assert expected_fragment in sanitized
    if forbidden_fragment:
        assert forbidden_fragment not in sanitized

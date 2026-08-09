from __future__ import annotations

from service.collector.source_quality import is_low_semantic_text, source_blocklist_reason


def test_is_low_semantic_text_flags_short_navigation_and_fragments() -> None:
    assert is_low_semantic_text("short text")[0] is True
    assert is_low_semantic_text("--- | --- | ---", min_chars=0) == (True, "symbol_fragment")
    assert is_low_semantic_text(
        "Welcome back. Continue with Google. Sign in to continue.",
        min_chars=0,
    ) == (True, "loading_or_auth_boilerplate")
    assert is_low_semantic_text(
        "Home Login Copyright All rights reserved Privacy Policy",
        min_chars=0,
    ) == (True, "navigation_boilerplate")
    assert is_low_semantic_text(
        "![通义灵码](https://example.com/image.png)",
        min_chars=0,
    ) == (True, "image_markdown")
    assert is_low_semantic_text(
        "Sign in Home/Tools/Coding Alternatives Pricing Reviews "
        "OpenAlt directory page lists tools and login navigation before any useful content.",
        min_chars=0,
    ) == (True, "navigation_directory")


def test_is_low_semantic_text_allows_substantive_quote() -> None:
    text = (
        "Cursor's enterprise documentation describes admin controls, repository indexing, "
        "privacy settings, SSO options, and team billing workflows for software teams. "
        "The page also explains how administrators configure workspace rules, manage user "
        "access, review security settings, and coordinate procurement for larger companies."
    )

    assert is_low_semantic_text(text) == (False, None)


def test_source_blocklist_reason_flags_auth_and_linkedin_urls() -> None:
    assert source_blocklist_reason("https://www.linkedin.com/login") == "blocked_host"
    assert source_blocklist_reason("https://x.com/search?q=ai+hardware") == "blocked_host"
    assert source_blocklist_reason("https://example.com/auth/login") == "blocked_auth_path"
    assert source_blocklist_reason("https://example.com/search?q=cursor+pricing") == "blocked_search_or_directory_path"
    assert source_blocklist_reason("https://example.com/webdir/tools/ai") == "blocked_search_or_directory_path"
    assert source_blocklist_reason("https://cursor.com/") == "bare_homepage"
    assert source_blocklist_reason("https://cursor.com/pricing") is None

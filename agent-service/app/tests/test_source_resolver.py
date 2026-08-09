from __future__ import annotations

import pytest

from service.collector.source_resolver import resolve_official_sources


@pytest.mark.asyncio
async def test_resolve_official_sources_enumerates_sitemap_and_nav(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fake_fetch_text_with_budget(*, url: str, http_client: object) -> str | None:
        del http_client
        if url == "https://cursor.com/blog/launch":
            return "<html><title>Cursor launch</title><body>Cursor announces launch.</body></html>"
        if url == "https://cursor.com/sitemap.xml":
            return (
                "<urlset>"
                "<url><loc>https://cursor.com/pricing</loc></url>"
                "<url><loc>https://cursor.com/docs/get-started</loc></url>"
                "<url><loc>https://cursor.com/changelog</loc></url>"
                "</urlset>"
            )
        if url == "https://cursor.com/":
            return (
                "<html><title>Cursor</title>"
                "<a href='/enterprise'>Enterprise</a>"
                "<a href='/security'>Security</a>"
                "</html>"
            )
        return None

    monkeypatch.setattr(
        "service.collector.source_resolver._fetch_text_with_budget",
        _fake_fetch_text_with_budget,
    )

    result = await resolve_official_sources(
        competitor_id="Cursor",
        competitor_name="Cursor",
        candidate_urls=["https://cursor.com/blog/launch"],
        key_page_budget=8,
    )

    assert result.validated_candidate_count == 1
    assert result.official_hosts == ["cursor.com"]
    assert "https://cursor.com/" in result.official_urls
    assert "https://cursor.com/pricing" in result.official_urls
    assert "https://cursor.com/docs/get-started" in result.official_urls


@pytest.mark.asyncio
async def test_resolve_official_sources_returns_empty_when_unverified(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fake_fetch_text_with_budget(*, url: str, http_client: object) -> str | None:
        del url, http_client
        return "<html><title>Generic market report</title><body>No brand mention.</body></html>"

    monkeypatch.setattr(
        "service.collector.source_resolver._fetch_text_with_budget",
        _fake_fetch_text_with_budget,
    )

    result = await resolve_official_sources(
        competitor_id="Cursor",
        competitor_name="Cursor",
        candidate_urls=["https://example.com/market-report"],
    )

    assert result.validated_candidate_count == 0
    assert result.official_hosts == []
    assert result.official_urls == []

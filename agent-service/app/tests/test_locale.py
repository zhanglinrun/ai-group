from __future__ import annotations

from service.locale import (
    country_for_language,
    plan_search_languages,
    source_locale,
    target_country_from_scope,
)


def test_source_locale_detects_china_tld_and_chinese_text() -> None:
    locale = source_locale(
        source_url="https://example.com.cn/news",
        span=None,
        sanitized_text="这是一段中文产品资料，适合国内市场分析。",
    )

    assert locale["country"] == "china"
    assert locale["language"] == "zh"
    assert locale["country_signal"] == "china_tld"


def test_source_locale_detects_global_english_source() -> None:
    locale = source_locale(
        source_url="https://example.com/news",
        span=None,
        sanitized_text="This is an English product source for global market analysis.",
    )

    assert locale["country"] == "global"
    assert locale["language"] == "en"


def test_source_locale_prefers_span_response_language() -> None:
    locale = source_locale(
        source_url="https://example.com/news",
        span={"response_language": "zh"},
        sanitized_text="English text but upstream response language is Chinese.",
    )

    assert locale["language"] == "zh"
    assert locale["language_signal"] == "span.response_language"


def test_target_country_from_scope_uses_explicit_market_scope_only() -> None:
    # Region comes ONLY from an explicit market scope; output language never implies a market.
    assert target_country_from_scope(market_scope="中国大陆") == "china"
    assert target_country_from_scope(market_scope="China mainland") == "china"
    assert target_country_from_scope(market_scope="global") is None
    # A Chinese-language run about a non-China market is NOT forced to China scope.
    assert target_country_from_scope(market_scope=None) is None
    assert target_country_from_scope(market_scope="北美市场") is None


def test_plan_search_languages_home_plus_global_by_default() -> None:
    # Home language first, then English as the global lingua franca.
    assert plan_search_languages(response_language="zh", market_scope=None) == ["zh", "en"]
    assert plan_search_languages(response_language="en", market_scope=None) == ["en"]
    assert plan_search_languages(response_language=None, market_scope=None) == ["en"]


def test_plan_search_languages_adds_market_languages() -> None:
    # Market scope adds languages regardless of output language (carrier ≠ market).
    assert plan_search_languages(response_language="en", market_scope="日本市场") == ["en", "ja"]
    assert plan_search_languages(response_language="en", market_scope="中国大陆") == ["en", "zh"]
    assert plan_search_languages(response_language="zh", market_scope="德国市场") == ["zh", "en", "de"]


def test_plan_search_languages_dedupes_caps_and_honors_extras() -> None:
    result = plan_search_languages(
        response_language="zh",
        market_scope="日本与韩国市场",
        extra_languages=["FR", "fr", "es"],
        max_languages=4,
    )
    # zh(home), en(global), ja+ko(market), then capped at 4 — extras spill over the cap.
    assert result == ["zh", "en", "ja", "ko"]


def test_country_for_language_maps_to_tavily_country() -> None:
    assert country_for_language("zh") == "china"
    assert country_for_language("ja") == "japan"
    assert country_for_language("ko") == "south korea"
    assert country_for_language("de") == "germany"
    # English stays global (no country localization), unknown languages too.
    assert country_for_language("en") is None
    assert country_for_language("xx") is None

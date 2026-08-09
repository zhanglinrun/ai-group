from __future__ import annotations

from typing import Literal, TypedDict
from urllib.parse import urlsplit

# Language contract (four layers):
# - ui_locale: frontend shell language (button/label/date formatting).
# - report_language (backward compatible key: response_language): report output language.
# - source_languages: retrieval fan-out languages.
# - source/evidence language: per-evidence original-language marker.
UILocale = Literal["zh-CN", "en-US"]
ReportLanguage = Literal["zh", "en"]
CountryCode = Literal["china", "global", "unknown"]

DEFAULT_UI_LOCALE: UILocale = "zh-CN"
DEFAULT_REPORT_LANGUAGE: ReportLanguage = "zh"
SUPPORTED_REPORT_LANGUAGES: frozenset[str] = frozenset({"zh", "en"})


class SourceLocale(TypedDict):
    country: CountryCode
    language: str
    country_signal: str
    language_signal: str
    host: str | None


_CHINA_TLD_SUFFIXES = (
    ".cn",
    ".com.cn",
    ".net.cn",
    ".org.cn",
    ".gov.cn",
    ".edu.cn",
    ".中国",
    ".公司",
    ".网络",
)
_CHINA_HOST_SUFFIXES = (
    "1688.com",
    "36kr.com",
    "aliyun.com",
    "baidu.com",
    "bilibili.com",
    "bytedance.com",
    "csdn.net",
    "doubao.com",
    "feishu.cn",
    "huawei.com",
    "jd.com",
    "qq.com",
    "sina.com.cn",
    "sohu.com",
    "taobao.com",
    "tencent.com",
    "tmall.com",
    "weixin.qq.com",
    "zhihu.com",
)


def normalize_report_language(value: object) -> ReportLanguage | None:
    if isinstance(value, str) and value in SUPPORTED_REPORT_LANGUAGES:
        return value
    return None


def resolve_report_language(
    *,
    report_language: object | None = None,
    response_language: object | None = None,
    user_query: str | None = None,
    default_language: ReportLanguage = DEFAULT_REPORT_LANGUAGE,
) -> ReportLanguage:
    """Resolve report output language with response_language compatibility."""
    normalized_report_language = normalize_report_language(report_language)
    if normalized_report_language is not None:
        return normalized_report_language
    normalized_response_language = normalize_report_language(response_language)
    if normalized_response_language is not None:
        return normalized_response_language
    if isinstance(user_query, str) and user_query.strip():
        return detect_language(user_query)
    return default_language


def detect_language(text: str) -> ReportLanguage:
    """Detect report output language from user-visible text."""
    if not isinstance(text, str):
        raise TypeError("detect_language expects text to be str")
    stripped = text.strip()
    if not stripped:
        return "en"
    chinese_chars = sum(1 for char in stripped if "\u4e00" <= char <= "\u9fff")
    alnum_chars = sum(1 for char in stripped if char.isalnum())
    if alnum_chars == 0:
        return "en"
    if chinese_chars >= 4:
        return "zh"
    return "zh" if chinese_chars >= 2 and chinese_chars / alnum_chars >= 0.10 else "en"


def detect_source_language(text: str) -> str:
    """Best-effort language detection for evidence/source text."""
    if not isinstance(text, str):
        raise TypeError("detect_source_language expects text to be str")
    stripped = text.strip()
    if not stripped:
        return "en"
    if any("\u3040" <= char <= "\u30ff" for char in stripped):
        return "ja"
    if any("\uac00" <= char <= "\ud7af" for char in stripped):
        return "ko"
    return detect_language(stripped)


# Multilingual breadth policy. Output language picks the "home" language; market scope and
# explicit hints add further languages so niche-market sources (e.g. the best product lives in a
# non-mainstream-language country) are reachable. Each language routes to its best engine/country.
_MARKET_LANGUAGE_MARKERS: tuple[tuple[tuple[str, ...], str], ...] = (
    (("中国", "国内", "大陆", "china", "mainland", "prc"), "zh"),
    (("日本", "日语", "日語", "japan", "japanese"), "ja"),
    (("韩国", "韓國", "한국", "korea", "korean"), "ko"),
    (("德国", "德语", "德語", "germany", "german", "deutsch"), "de"),
    (("法国", "法语", "法語", "france", "french"), "fr"),
    (("西班牙", "拉美", "spain", "spanish", "latin america", "hispanic"), "es"),
)
# ISO 639-1 language -> Tavily `country` (its localization lever). English stays global (None).
_TAVILY_COUNTRY_BY_LANGUAGE: dict[str, str] = {
    "zh": "china",
    "ja": "japan",
    "ko": "south korea",
    "de": "germany",
    "fr": "france",
    "es": "spain",
}
_MAX_SEARCH_LANGUAGES = 4


def country_for_language(language: str) -> str | None:
    """Map an ISO 639-1 language to a Tavily country localization. English = global (None)."""
    if not isinstance(language, str):
        raise TypeError("country_for_language expects language to be str")
    return _TAVILY_COUNTRY_BY_LANGUAGE.get(language.strip().casefold())


def languages_from_market_scope(market_scope: object) -> list[str]:
    scope = market_scope if isinstance(market_scope, str) else ""
    lowered = scope.casefold()
    out: list[str] = []
    for markers, language in _MARKET_LANGUAGE_MARKERS:
        if any(marker in lowered for marker in markers) and language not in out:
            out.append(language)
    return out


def _normalize_languages(languages: list[str], *, max_languages: int) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for raw in languages:
        if not isinstance(raw, str):
            continue
        key = raw.strip().casefold()
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(key)
        if len(result) >= max_languages:
            break
    return result


def plan_source_languages(
    *,
    report_language: str | None,
    market_scope: object,
    extra_languages: list[str] | None = None,
    max_languages: int = _MAX_SEARCH_LANGUAGES,
) -> list[str]:
    """Ordered source-language set for multilingual retrieval."""
    home = report_language if normalize_report_language(report_language) is not None else "en"
    ordered = [home, "en"]
    ordered.extend(languages_from_market_scope(market_scope))
    if extra_languages:
        ordered.extend(extra_languages)
    return _normalize_languages(ordered, max_languages=max_languages)


def plan_search_languages(
    *,
    response_language: str | None,
    market_scope: object,
    extra_languages: list[str] | None = None,
    max_languages: int = _MAX_SEARCH_LANGUAGES,
) -> list[str]:
    """Backward-compatible alias for source language planning."""
    return plan_source_languages(
        report_language=response_language,
        market_scope=market_scope,
        extra_languages=extra_languages,
        max_languages=max_languages,
    )


def target_country_from_scope(*, market_scope: object) -> str | None:
    """Region emphasis is derived ONLY from an explicit market scope.

    Output language (response_language) localizes the report; it must NOT constrain
    which markets/languages are in scope. A Chinese-speaking user asking about a global
    topic still wants global sources — language is the carrier, not the market.
    """
    scope = market_scope if isinstance(market_scope, str) else ""
    lowered = scope.casefold()
    if any(marker in lowered for marker in ("china", "mainland", "中国", "国内", "大陆")):
        return "china"
    return None


def _host_from_url(source_url: str | None) -> str | None:
    if not isinstance(source_url, str) or not source_url.strip():
        return None
    try:
        parsed = urlsplit(source_url.strip())
    except ValueError:
        return None
    host = parsed.hostname
    return host.casefold() if isinstance(host, str) and host.strip() else None


def _country_from_host(host: str | None) -> tuple[CountryCode, str]:
    if host is None:
        return "unknown", "missing_url"
    if host.endswith(_CHINA_TLD_SUFFIXES):
        return "china", "china_tld"
    if any(host == suffix or host.endswith(f".{suffix}") for suffix in _CHINA_HOST_SUFFIXES):
        return "china", "known_china_host"
    return "global", "host"


def _language_from_span(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    for key in ("source_language", "detected_language", "response_language"):
        language_raw = span.get(key)
        if isinstance(language_raw, str) and language_raw.strip():
            return language_raw.strip().casefold()
    return None


def should_translate_evidence(*, report_language: str | None, source_language: str | None) -> bool:
    if not isinstance(source_language, str) or not source_language.strip():
        return False
    normalized_source = source_language.strip().casefold()
    normalized_report = normalize_report_language(report_language)
    if normalized_report is None:
        return False
    return normalized_source != normalized_report


def source_locale(
    *,
    source_url: str | None,
    span: dict[str, object] | None,
    sanitized_text: str,
) -> SourceLocale:
    host = _host_from_url(source_url)
    country, country_signal = _country_from_host(host)
    span_language = _language_from_span(span)
    if span_language is not None:
        language = span_language
        language_signal = "span.language"
    else:
        language = detect_source_language(sanitized_text)
        language_signal = "sanitized_text"
    return {
        "country": country,
        "language": language,
        "country_signal": country_signal,
        "language_signal": language_signal,
        "host": host,
    }

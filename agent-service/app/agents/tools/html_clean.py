from __future__ import annotations

import re

from bs4 import BeautifulSoup
from readability import Document

from service.collector.errors import ChannelError

# Local main-text extraction so fetch_url no longer depends on a paid extract
# API. readability picks the article body; BeautifulSoup strips chrome/noise
# that survives, and post_clean_text removes cookie/legal boilerplate that
# otherwise pollutes evidence.

_NOISE_TAGS = frozenset({
    "script",
    "style",
    "noscript",
    "iframe",
    "object",
    "embed",
    "applet",
    "svg",
    "head",
})

_NOISE_ATTRS = frozenset({
    "onclick",
    "onload",
    "onmouseover",
    "data-gtm",
    "data-ga",
    "data-fbq",
})

_NOISE_ARIA_ROLES = frozenset({
    "complementary",
    "contentinfo",
})

_NOISE_CONTAINER_KEYWORDS = (
    "ad-",
    "ads-",
    "cookie",
    "gdpr",
    "popup",
    "modal",
    "newsletter",
    "subscribe",
    "banner",
    "tracking",
)

_KEEP_SEMANTIC = frozenset({
    "main",
    "article",
    "section",
    "h1",
    "h2",
    "h3",
    "h4",
    "p",
    "li",
    "dt",
    "dd",
    "table",
    "th",
    "td",
    "tr",
    "blockquote",
    "pre",
    "code",
    "time",
})

_COOKIE_PATTERN = re.compile(
    r"(we use cookies|cookie policy|accept all cookies|manage preferences"
    r"|by continuing.*?agree|privacy policy applies)[^\n]{0,200}",
    re.IGNORECASE,
)
_LEGAL_PATTERN = re.compile(
    r"(all rights reserved|copyright ©|terms of (use|service)|©\s*\d{4})[^\n]{0,100}",
    re.IGNORECASE,
)
_WHITESPACE_NORM = re.compile(r"\n{3,}")


def _pre_clean_html(html: str) -> str:
    soup = BeautifulSoup(html, "lxml")
    for tag in soup.find_all(_NOISE_TAGS):
        tag.decompose()
    for tag in soup.find_all(True):
        attrs = tag.attrs if isinstance(tag.attrs, dict) else {}
        class_raw = attrs.get("class", [])
        cls = " ".join(class_raw) if isinstance(class_raw, list) else str(class_raw)
        id_raw = attrs.get("id", "")
        tid = id_raw if isinstance(id_raw, str) else str(id_raw)
        if any(kw in cls or kw in tid for kw in _NOISE_CONTAINER_KEYWORDS):
            tag.decompose()
    for tag in soup.find_all(attrs={"role": True}):
        attrs = tag.attrs if isinstance(tag.attrs, dict) else {}
        if attrs.get("role") in _NOISE_ARIA_ROLES:
            tag.decompose()
    for tag in soup.find_all(True):
        if not isinstance(tag.attrs, dict):
            continue
        for attr in list(tag.attrs):
            if attr in _NOISE_ATTRS or attr.startswith("data-track"):
                del tag[attr]
    return str(soup)


def _extract_semantic_text(soup: BeautifulSoup) -> str:
    parts: list[str] = []
    for tag in soup.find_all(_KEEP_SEMANTIC):
        if any(p.name in _KEEP_SEMANTIC for p in tag.parents):
            continue
        text = tag.get_text(separator=" ", strip=True)
        if text:
            parts.append(text)
    return "\n".join(parts)


def post_clean_text(text: str) -> str:
    cleaned = _COOKIE_PATTERN.sub("", text)
    cleaned = _LEGAL_PATTERN.sub("", cleaned)
    cleaned = _WHITESPACE_NORM.sub("\n\n", cleaned)
    return cleaned.strip()


def extract_main_text(html: str) -> str:
    cleaned_html = _pre_clean_html(html)
    doc = Document(cleaned_html)
    summary_html = doc.summary(html_partial=True)
    summary_soup = BeautifulSoup(summary_html, "lxml")
    semantic_text = _extract_semantic_text(summary_soup)
    if not semantic_text:
        semantic_text = summary_soup.get_text(separator="\n", strip=True)
    if not semantic_text:
        fallback_soup = BeautifulSoup(cleaned_html, "lxml")
        semantic_text = fallback_soup.get_text(separator="\n", strip=True)
    if not semantic_text:
        raise ChannelError("fetch_url extracted empty text from HTML.")
    return post_clean_text(semantic_text)

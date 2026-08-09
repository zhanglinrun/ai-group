from __future__ import annotations

import re
from urllib.parse import urlsplit

MIN_EXTRACTED_TEXT_CHARS = 160
NAVIGATION_WORDS = frozenset(
    {
        "coding",
        "home",
        "login",
        "sign in",
        "tools",
        "copyright",
        "all rights reserved",
        "privacy policy",
        "terms of service",
        "table of contents",
    }
)
LOW_SEMANTIC_PHRASES = frozenset(
    {
        "welcome back",
        "continue with google",
        "continue with apple",
        "sign in to continue",
        "log in to continue",
        "enable javascript",
        "loading...",
        "please wait",
        "sign in home",
    }
)
# A real article body carries prose; pages dominated by site footer / contact
# fields or upload-portal promos slipped past the length gate and polluted
# production evidence (e.g. ByteDance footer, 原创力文档 upload boilerplate).
CONTACT_FOOTER_MARKERS = frozenset(
    {
        "网站地图",
        "法律声明",
        "联系方式",
        "联系电话",
        "客服电话",
        "传真",
        "版权所有",
        "绿色通道",
        "site map",
        "all rights reserved",
    }
)
DOC_PORTAL_PROMO_PHRASES = frozenset(
    {
        "原创力文档",
        "上传者qq群",
        "侵权专属",
        "无忧创作",
        "知识共享、知识服务",
        "上传文档赚钱",
    }
)
BLOCKED_HOST_SUFFIXES = frozenset(
    {
        "linkedin.com",
        "x.com",
        "twitter.com",
        "book118.com",
        "xun296.com",
    }
)
BLOCKED_PATH_MARKERS = frozenset(
    {
        "/login",
        "/signin",
        "/auth",
        "/checkpoint",
        "/uas/login",
    }
)
BLOCKED_DIRECTORY_PATH_MARKERS = frozenset(
    {
        "/search",
        "/webdir",
    }
)
WORD_PATTERN = re.compile(r"[A-Za-z\u4e00-\u9fff][A-Za-z\u4e00-\u9fff0-9_-]*")
SYMBOL_FRAGMENT_PATTERN = re.compile(r"^[\s\-\|:;,_#~`.*=+\\/\[\]{}()<>]+$")
MARKDOWN_IMAGE_PATTERN = re.compile(r"!\[[^\]]*]\([^)]*\)")
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]+]\([^)]*\)")


def _markdown_image_dominant(compact: str) -> bool:
    image_matches = MARKDOWN_IMAGE_PATTERN.findall(compact)
    if not image_matches:
        return False
    image_chars = sum(len(item) for item in image_matches)
    words = WORD_PATTERN.findall(MARKDOWN_IMAGE_PATTERN.sub(" ", compact))
    return image_chars / max(len(compact), 1) >= 0.25 and len(words) < 40


def _link_density_high(compact: str) -> bool:
    link_chars = sum(len(item) for item in MARKDOWN_LINK_PATTERN.findall(compact))
    if link_chars == 0:
        return False
    return link_chars / max(len(compact), 1) >= 0.35


def _looks_like_navigation_directory(*, lower: str, compact: str, words: list[str]) -> bool:
    prefix = lower[:160]
    starts_with_nav = (
        prefix.startswith("sign in ")
        or prefix.startswith("home ")
        or "sign in home" in prefix
        or "home tools coding" in prefix
        or "home/tools/coding" in prefix
    )
    directory_markers = (
        "tools/coding" in prefix
        or "tools coding" in prefix
        or "alternatives" in prefix
        or "reviews" in prefix
    )
    navigation_hits = sum(1 for word in NAVIGATION_WORDS if word in lower)
    prefix_navigation_hits = sum(1 for word in NAVIGATION_WORDS if word in prefix)
    return (
        starts_with_nav
        and directory_markers
        and navigation_hits >= 3
        and (_link_density_high(compact) or prefix_navigation_hits >= 3 or len(words) < 180)
    )


def is_low_semantic_text(
    text: str,
    *,
    min_chars: int = MIN_EXTRACTED_TEXT_CHARS,
) -> tuple[bool, str | None]:
    compact = " ".join(text.split())
    if not compact:
        return True, "empty"
    if SYMBOL_FRAGMENT_PATTERN.fullmatch(compact):
        return True, "symbol_fragment"
    if _markdown_image_dominant(compact):
        return True, "image_markdown"
    if len(compact) < min_chars:
        return True, "too_short"
    lower = compact.lower()
    navigation_hits = sum(1 for word in NAVIGATION_WORDS if word in lower)
    words = WORD_PATTERN.findall(compact)
    if _looks_like_navigation_directory(lower=lower, compact=compact, words=words):
        return True, "navigation_directory"
    if navigation_hits >= 3 and len(words) < 80:
        return True, "navigation_boilerplate"
    if any(phrase in lower for phrase in LOW_SEMANTIC_PHRASES):
        return True, "loading_or_auth_boilerplate"
    if any(phrase in lower for phrase in DOC_PORTAL_PROMO_PHRASES):
        return True, "doc_portal_promo"
    contact_footer_hits = sum(1 for marker in CONTACT_FOOTER_MARKERS if marker in lower)
    if contact_footer_hits >= 3 and len(words) < 120:
        return True, "contact_footer_boilerplate"
    return False, None


def source_blocklist_reason(source_url: str | None) -> str | None:
    if source_url is None:
        return None
    parsed = urlsplit(source_url)
    host = parsed.netloc.lower().removeprefix("www.")
    path = parsed.path.lower()
    query = parsed.query.lower()
    if not host:
        return None
    if host in BLOCKED_HOST_SUFFIXES or any(host.endswith(f".{suffix}") for suffix in BLOCKED_HOST_SUFFIXES):
        return "blocked_host"
    if any(marker in path for marker in BLOCKED_PATH_MARKERS):
        return "blocked_auth_path"
    normalized_path = path.rstrip("/")
    if any(
        marker == normalized_path
        or normalized_path.endswith(marker)
        or f"{marker}/" in normalized_path
        for marker in BLOCKED_DIRECTORY_PATH_MARKERS
    ):
        return "blocked_search_or_directory_path"
    if query and ("q=" in query or "query=" in query) and "search" in normalized_path:
        return "blocked_search_or_directory_path"
    if path in {"", "/"} and not query:
        return "bare_homepage"
    return None

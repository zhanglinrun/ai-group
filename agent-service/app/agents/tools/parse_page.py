from __future__ import annotations

from urllib.parse import urlsplit

from service.collector.base import SourceType


REVIEW_HOST_KEYWORDS = (
    "forum",
    "reddit",
    "community",
    "review",
    "discuss",
    "news.ycombinator",
    "g2",
    "capterra",
    "trustpilot",
    "producthunt",
    "zhihu",
    "v2ex",
    "csdn",
    "juejin",
    "segmentfault",
    "stackshare",
    "sourceforge",
)
DOC_PATH_KEYWORDS = ("/docs", "/api", "/reference")
DOC_HOSTS = {"help.aliyun.com", "docs.github.com"}
PRICING_PATH_KEYWORDS = ("/pricing", "/plans")
MARKET_REPORT_HOST_KEYWORDS = (
    "caict",
    "gartner",
    "idc",
    "forrester",
    "statista",
    "questmobile",
    "iresearch",
    "analysys",
    "researchandmarkets",
)
MARKET_REPORT_PATH_KEYWORDS = (
    "/report",
    "/reports",
    "/whitepaper",
    "/white-paper",
    "/insight",
    "/insights",
    "/研究报告",
    "/白皮书",
)
OFFICIAL_HOSTS_BY_COMPETITOR: dict[str, set[str]] = {
    "cursor": {"cursor.com", "www.cursor.com"},
    "github_copilot": {"github.com", "docs.github.com"},
    "copilot": {"github.com", "docs.github.com"},
    "windsurf": {"windsurf.com", "www.windsurf.com", "codeium.com", "www.codeium.com"},
    "tongyi_lingma": {"lingma.aliyun.com", "tongyi.aliyun.com", "help.aliyun.com"},
    "通义灵码": {"lingma.aliyun.com", "tongyi.aliyun.com", "help.aliyun.com"},
    "qoder": {"lingma.aliyun.com", "tongyi.aliyun.com", "help.aliyun.com"},
    "qoder_cn": {"lingma.aliyun.com", "tongyi.aliyun.com", "help.aliyun.com"},
    "baidu_comate": {"comate.baidu.com"},
    "wenxin_comate": {"comate.baidu.com"},
    "文心_comate": {"comate.baidu.com"},
    "文心comate": {"comate.baidu.com"},
    "doubao": {"doubao.com", "www.doubao.com", "developer.volcengine.com"},
    "doubao_ai_coding_assistant": {"doubao.com", "www.doubao.com", "developer.volcengine.com"},
    "豆包": {"doubao.com", "www.doubao.com", "developer.volcengine.com"},
    "trae": {"trae.ai", "www.trae.ai"},
}
DEFAULT_OFFICIAL_HOSTS: set[str] = {
    host
    for hosts in OFFICIAL_HOSTS_BY_COMPETITOR.values()
    for host in hosts
}


def _normalize_host(host: str) -> str:
    return host.lower().removeprefix("www.")


def _normalize_competitor_key(value: str) -> str:
    return (
        value.strip()
        .lower()
        .replace(" ", "_")
        .replace("-", "_")
        .replace("/", "_")
    )


_HEURISTIC_OFFICIAL_TLDS = ("com", "ai", "cn", "io", "dev", "co")
_GENERIC_COMPETITOR_TOKENS = frozenset(
    {"com", "www", "github", "ai", "the", "app", "inc", "lab", "labs", "code", "coding"}
)


def _heuristic_official_hosts(competitor_id: str) -> set[str]:
    key = _normalize_competitor_key(competitor_id)
    tokens = [
        token
        for token in key.split("_")
        if len(token) >= 4 and token not in _GENERIC_COMPETITOR_TOKENS and token.isascii()
    ]
    return {f"{token}.{tld}" for token in tokens for tld in _HEURISTIC_OFFICIAL_TLDS}


def official_hosts_for_competitor(competitor_id: str | None) -> set[str]:
    if not isinstance(competitor_id, str) or not competitor_id.strip():
        return set(DEFAULT_OFFICIAL_HOSTS)
    key = _normalize_competitor_key(competitor_id)
    hosts = set(OFFICIAL_HOSTS_BY_COMPETITOR.get(key, set()))
    if not hosts:
        # Dynamic competitors outside the curated map: derive conservative candidate
        # official hosts from ASCII name tokens so their own domains can still be
        # recognized. Generic tokens are excluded to avoid false official matches.
        hosts.update(_heuristic_official_hosts(competitor_id))
    return hosts


def source_matches_competitor(
    *,
    source_url: str | None,
    competitor_id: str | None,
) -> bool | None:
    if not source_url or not competitor_id:
        return None
    parsed = urlsplit(source_url)
    host = _normalize_host(parsed.netloc)
    if not host:
        return None
    official_hosts = {_normalize_host(item) for item in official_hosts_for_competitor(competitor_id)}
    if host in official_hosts or any(host.endswith(f".{official}") for official in official_hosts):
        return True
    return False


def infer_source_type(
    *,
    source_url: str | None,
    official_hosts: set[str] | None = None,
) -> SourceType:
    if not source_url:
        return "article"
    parsed = urlsplit(source_url)
    host = _normalize_host(parsed.netloc)
    path = parsed.path.lower()
    normalized_official_hosts = (
        {_normalize_host(item) for item in official_hosts}
        if official_hosts
        else set()
    )
    if any(keyword in host for keyword in REVIEW_HOST_KEYWORDS):
        return "public_review"
    if any(keyword in host for keyword in MARKET_REPORT_HOST_KEYWORDS) or any(
        keyword in path for keyword in MARKET_REPORT_PATH_KEYWORDS
    ):
        return "market_report"
    if normalized_official_hosts and (
        host in normalized_official_hosts
        or any(host.endswith(f".{official}") for official in normalized_official_hosts)
    ):
        if host in DOC_HOSTS or any(keyword in path for keyword in DOC_PATH_KEYWORDS):
            return "docs"
        if any(keyword in path for keyword in PRICING_PATH_KEYWORDS):
            return "pricing_page"
        return "official_site"
    if any(keyword in path for keyword in PRICING_PATH_KEYWORDS):
        return "pricing_page"
    return "article"

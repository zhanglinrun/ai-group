from __future__ import annotations

from dataclasses import dataclass

import httpx
import pytest
from tavily.errors import ForbiddenError as TavilyForbiddenError

from agents.tools.fetch_url import FetchUrlChannel
from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor, source_matches_competitor
from agents.tools.rerank_bocha import _request_bocha_rerank, rerank
from agents.tools.search_bocha import BochaSearchChannel
from agents.tools.search_router import SearchWebRouterChannel, _reset_provider_cooldowns_for_tests
from agents.tools.search_serper import SerperSearchChannel
from agents.tools.search_web import TavilySearchChannel, _tavily_search
from core.config import settings
from service.collector.base import CollectorObservation, CollectorSnippet, ToolObservationResult
from service.collector.errors import ChannelError, RateLimited, RobotsBlocked
from service.collector.http_client import CollectorHTTPClient, FetchResponse
from service.collector.registry import ChannelRegistry, _register_builtin_channels


@pytest.fixture(autouse=True)
def _serper_key_disabled_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    # Keep routing deterministic and env-independent: Serper only enters the chain when a
    # test explicitly sets the key, so existing Bocha/Tavily expectations stay unchanged
    # regardless of whether a real SERPER_API_KEY is present in the loaded .env.
    monkeypatch.setattr(settings, "SERPER_API_KEY", None)


@pytest.fixture(autouse=True)
def _reset_search_router_provider_cooldown_state() -> None:
    _reset_provider_cooldowns_for_tests()
    yield
    _reset_provider_cooldowns_for_tests()


@dataclass
class _FakeLimiter:
    host: str | None = None
    timeout_seconds: float | None = None

    async def acquire(self, host: str, *, timeout_seconds: float | None = None) -> None:
        self.host = host
        self.timeout_seconds = timeout_seconds


class _FakeSerperHTTPErrorResponse:
    def __init__(self, *, status_code: int, text: str) -> None:
        self.status_code = status_code
        self.text = text

    def raise_for_status(self) -> None:
        request = httpx.Request("POST", "https://google.serper.dev/search")
        response = httpx.Response(self.status_code, text=self.text, request=request)
        raise httpx.HTTPStatusError(self.text, request=request, response=response)

    def json(self) -> dict[str, object]:
        return {}


class _AllowRobotsGate:
    async def ensure_allowed(self, *, target_url: str, user_agent: str, client: object) -> None:
        del target_url, user_agent, client


class _BlockRobotsGate:
    async def ensure_allowed(self, *, target_url: str, user_agent: str, client: object) -> None:
        del user_agent, client
        raise RobotsBlocked(f"blocked by robots: {target_url}")


@dataclass
class _FakeHTTPClient:
    fetch_response: FetchResponse

    @property
    def client(self) -> object:
        return object()

    async def fetch_text(self, url: str, *, retries: int = 1) -> FetchResponse:
        del retries
        return FetchResponse(
            url=url,
            status_code=self.fetch_response.status_code,
            text=self.fetch_response.text,
            content_type=self.fetch_response.content_type,
        )


class _FakeBochaResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload
        self.status_code = 200
        self.text = "ok"

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return self._payload


class _FakeBochaAsyncClient:
    def __init__(self, response: _FakeBochaResponse) -> None:
        self.response = response
        self.post_kwargs: dict[str, object] | None = None

    async def __aenter__(self) -> "_FakeBochaAsyncClient":
        return self

    async def __aexit__(self, exc_type: object, exc: object, tb: object) -> None:
        del exc_type, exc, tb

    async def post(self, url: str, **kwargs: object) -> _FakeBochaResponse:
        self.post_kwargs = {"url": url, **kwargs}
        return self.response


class _FakeSearchChannel:
    def __init__(
        self,
        *,
        provider: str,
        source_url: str = "https://example.com/result",
        exc: Exception | None = None,
    ) -> None:
        self.provider = provider
        self.source_url = source_url
        self.exc = exc
        self.calls: list[dict[str, object]] = []

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        self.calls.append(dict(kwargs))
        if self.exc is not None:
            raise self.exc
        query = kwargs.get("query")
        return CollectorObservation(
            channel=f"{self.provider}_search",
            args=dict(kwargs),
            result=ToolObservationResult(
                snippets=[
                    CollectorSnippet(
                        quote=f"{self.provider} result for {query}",
                        sanitized_text=f"{self.provider} result for {query}",
                        source_url=self.source_url,
                        source_title=f"{self.provider} title",
                        source_type="article",
                        desensitized=True,
                        metadata={"source": f"{self.provider}_search"},
                    )
                ],
                metadata={"provider": self.provider},
            ),
        )


class _FakeTavilyQuotaClient:
    def __init__(self, api_key: str | None) -> None:
        self.api_key = api_key

    def search(self, **kwargs: object) -> dict[str, object]:
        del kwargs
        raise TavilyForbiddenError("This request exceeds your plan's set usage limit.")


@pytest.mark.asyncio
async def test_fetch_url_channel_respects_robots_gate(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    limiter = _FakeLimiter()
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: limiter)
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _BlockRobotsGate())
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://example.com",
                status_code=200,
                text="<html><body>ok</body></html>",
                content_type="text/html",
            )
        ),
    )

    with pytest.raises(RobotsBlocked):
        await channel.invoke(url="https://example.com/docs/a")


@pytest.mark.asyncio
async def test_fetch_url_channel_records_host_for_qps(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    limiter = _FakeLimiter()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_TAVILY_FALLBACK_ENABLED", True)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: limiter)
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    # Non-HTML content_type makes the local httpx path bail out deterministically,
    # so this exercises the Tavily fallback branch and the per-host limiter.
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://cursor.com/pricing",
                status_code=200,
                text="%PDF-1.7 binary",
                content_type="application/pdf",
            )
        ),
    )
    async def _fake_tavily_extract(*, url: str, query: str | None) -> dict[str, object]:
        del query
        return {
            "results": [
                {
                    "url": url,
                    "raw_content": (
                        "Cursor pricing page content with enough substance for extraction. "
                        "It describes enterprise plans, usage limits, admin controls, privacy, "
                        "security, and billing details for team buyers in multiple paragraphs."
                    ),
                }
            ]
        }

    monkeypatch.setattr("agents.tools.fetch_url._tavily_extract", _fake_tavily_extract)

    observation = await channel.invoke(
        url="https://cursor.com/pricing",
        competitor_id="comp_cursor",
    )
    assert limiter.host == "cursor.com"
    assert limiter.timeout_seconds is not None
    assert observation.result.snippets[0].source_type == "pricing_page"
    assert observation.result.snippets[0].metadata["source"] == "tavily_extract"


@pytest.mark.asyncio
async def test_fetch_url_channel_uses_local_httpx_extract(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    limiter = _FakeLimiter()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", None)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: limiter)
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    html = (
        "<html><head><title>Cursor Pricing</title></head><body><article>"
        "<h1>Cursor Pricing Plans</h1>"
        "<p>Cursor offers a Hobby free tier, a Pro plan at twenty dollars per month, "
        "and a Business plan at forty dollars per user per month with centralized billing, "
        "SSO, and admin controls for engineering teams.</p>"
        "<p>The Business plan adds enforced privacy mode, SAML SSO, and analytics so "
        "engineering leaders can audit adoption across the whole organization.</p>"
        "</article></body></html>"
    )
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://cursor.com/pricing",
                status_code=200,
                text=html,
                content_type="text/html",
            )
        ),
    )

    observation = await channel.invoke(
        url="https://cursor.com/pricing",
        competitor_id="comp_cursor",
    )
    assert limiter.host == "cursor.com"
    assert observation.result.snippets[0].metadata["source"] == "httpx_extract"
    assert observation.result.snippets[0].source_type == "pricing_page"
    assert "Business plan" in observation.result.snippets[0].quote


@pytest.mark.asyncio
async def test_fetch_url_channel_wraps_local_parser_attribute_errors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    channel = FetchUrlChannel()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", None)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: _FakeLimiter())
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://github.com/features/copilot",
                status_code=200,
                text="<html><body><main>GitHub Copilot feature page</main></body></html>",
                content_type="text/html",
            )
        ),
    )

    def _raise_attribute_error(html: str) -> str:
        del html
        raise AttributeError("'NoneType' object has no attribute 'get'")

    monkeypatch.setattr("agents.tools.fetch_url.extract_main_text", _raise_attribute_error)

    with pytest.raises(ChannelError, match="local HTML extraction failed: AttributeError"):
        await channel.invoke(url="https://github.com/features/copilot")


@pytest.mark.asyncio
async def test_fetch_url_channel_rejects_low_quality_extract(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_TAVILY_FALLBACK_ENABLED", True)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: _FakeLimiter())
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://example.com",
                status_code=200,
                text="%PDF-1.7 binary",
                content_type="application/pdf",
            )
        ),
    )

    async def _fake_tavily_extract(*, url: str, query: str | None) -> dict[str, object]:
        del url, query
        return {"results": [{"url": "https://example.com", "raw_content": "Copyright All rights reserved"}]}

    monkeypatch.setattr("agents.tools.fetch_url._tavily_extract", _fake_tavily_extract)

    with pytest.raises(ChannelError, match="too short"):
        await channel.invoke(url="https://example.com")


@pytest.mark.asyncio
async def test_fetch_url_channel_skips_tavily_when_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_TAVILY_FALLBACK_ENABLED", False)
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_SEARCH_FALLBACK_ENABLED", False)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: _FakeLimiter())
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://cursor.com/pricing",
                status_code=200,
                text="%PDF-1.7 binary",
                content_type="application/pdf",
            )
        ),
    )

    tavily_called = False

    async def _fake_tavily_extract(*, url: str, query: str | None) -> dict[str, object]:
        del url, query
        nonlocal tavily_called
        tavily_called = True
        return {"results": []}

    monkeypatch.setattr("agents.tools.fetch_url._tavily_extract", _fake_tavily_extract)

    with pytest.raises(ChannelError, match="tavily fallback skipped"):
        await channel.invoke(url="https://cursor.com/pricing")
    assert tavily_called is False


@pytest.mark.asyncio
async def test_fetch_url_channel_uses_search_snippet_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    channel = FetchUrlChannel()
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_TAVILY_FALLBACK_ENABLED", False)
    monkeypatch.setattr(settings, "COLLECTOR_FETCH_SEARCH_FALLBACK_ENABLED", True)
    monkeypatch.setattr("agents.tools.fetch_url._get_per_host_limiter", lambda: _FakeLimiter())
    monkeypatch.setattr("agents.tools.fetch_url._get_robots_gate", lambda: _AllowRobotsGate())
    monkeypatch.setattr(
        "agents.tools.fetch_url.get_collector_http_client",
        lambda: _FakeHTTPClient(
            FetchResponse(
                url="https://www.jetbrains.com/ide-services/ai-enterprise/",
                status_code=200,
                text="%PDF-1.7 binary",
                content_type="application/pdf",
            )
        ),
    )

    call_args: dict[str, object] = {}

    class _FakeRegistry:
        async def invoke(self, action: str, *, args: dict[str, object]) -> CollectorObservation:
            call_args["action"] = action
            call_args["args"] = dict(args)
            return CollectorObservation(
                channel="search_web",
                args=args,
                result=ToolObservationResult(
                    snippets=[
                        CollectorSnippet(
                            quote="JetBrains AI enterprise users report strong IDE integration and policy controls.",
                            sanitized_text=(
                                "JetBrains AI enterprise users report strong IDE integration and policy controls."
                            ),
                            source_url="https://www.g2.com/products/jetbrains-ai-assistant/reviews",
                            source_title="G2 Reviews",
                            source_type="public_review",
                            desensitized=True,
                            metadata={"source": "serper_search"},
                        )
                    ],
                    metadata={"provider": "serper"},
                ),
            )

    monkeypatch.setattr("service.collector.registry.get_channel_registry", lambda: _FakeRegistry())

    observation = await channel.invoke(
        url="https://www.jetbrains.com/ide-services/ai-enterprise/",
        competitor_id="JetBrains AI",
        dimension="user_feedback",
        query="JetBrains AI enterprise capabilities security compliance deployment",
    )

    assert call_args["action"] == "search_web"
    args = call_args["args"]
    assert isinstance(args, dict)
    assert args["query"] == (
        "site:www.jetbrains.com JetBrains AI enterprise capabilities security compliance deployment"
    )
    assert args["query_variants"] == ["JetBrains AI enterprise capabilities security compliance deployment"]
    assert observation.result.snippets[0].metadata["source"] == "search_snippet_fallback"
    assert observation.result.metadata["source"] == "search_snippet_fallback"
    assert observation.result.snippets[0].source_type == "public_review"


def test_collector_http_client_sets_user_agent_header() -> None:
    client = CollectorHTTPClient(user_agent="XiongDoctor-Researcher/0.1 test", timeout_seconds=3)
    assert client.client.headers.get("User-Agent") == "XiongDoctor-Researcher/0.1 test"


def test_source_type_mapping_rules() -> None:
    assert (
        infer_source_type(
            source_url="https://cursor.com/docs/api",
            official_hosts={"cursor.com"},
        )
        == "docs"
    )
    assert (
        infer_source_type(
            source_url="https://cursor.com/pricing",
            official_hosts={"cursor.com"},
        )
        == "pricing_page"
    )
    assert (
        infer_source_type(
            source_url="https://www.cursor.com/pricing",
            official_hosts=official_hosts_for_competitor("Cursor"),
        )
        == "pricing_page"
    )
    assert source_matches_competitor(
        source_url="https://cursor.com/pricing",
        competitor_id="Cursor",
    ) is True
    assert source_matches_competitor(
        source_url="https://billingplatform.com/blog/pricing",
        competitor_id="Cursor",
    ) is False
    assert source_matches_competitor(
        source_url="https://cursoranalytics.com/platform-overview",
        competitor_id="Cursor",
    ) is False
    assert (
        infer_source_type(
            source_url="https://community.example.com/thread/1",
            official_hosts=None,
        )
        == "public_review"
    )
    assert (
        infer_source_type(
            source_url="https://forum.cursor.com/t/how-does-the-new-pricing-affect-business-plans/108774",
            official_hosts=official_hosts_for_competitor("Cursor"),
        )
        == "public_review"
    )
    assert (
        infer_source_type(
            source_url="https://www.g2.com/products/cursor/reviews",
            official_hosts=None,
        )
        == "public_review"
    )


def test_official_hosts_heuristic_for_dynamic_competitor() -> None:
    openai_hosts = official_hosts_for_competitor("OpenAI")
    assert "openai.com" in openai_hosts
    assert (
        infer_source_type(
            source_url="https://openai.com/docs/guides",
            official_hosts=openai_hosts,
        )
        == "docs"
    )
    assert source_matches_competitor(
        source_url="https://openai.com/pricing",
        competitor_id="OpenAI",
    ) is True
    # An unrelated vendor domain must not be treated as this competitor's official source.
    assert source_matches_competitor(
        source_url="https://github.com/features",
        competitor_id="OpenAI",
    ) is False
    assert (
        infer_source_type(
            source_url="https://github.com/features",
            official_hosts=official_hosts_for_competitor("OpenAI"),
        )
        == "article"
    )


def test_tongyi_lingma_aliyun_help_is_official_docs() -> None:
    official_hosts = official_hosts_for_competitor("通义灵码")
    assert "help.aliyun.com" in official_hosts
    assert (
        infer_source_type(
            source_url="https://help.aliyun.com/zh/lingma/product-overview",
            official_hosts=official_hosts,
        )
        == "docs"
    )
    assert source_matches_competitor(
        source_url="https://help.aliyun.com/zh/lingma/product-overview",
        competitor_id="通义灵码",
    ) is True
    assert source_matches_competitor(
        source_url="https://developer.aliyun.com/article/1662698",
        competitor_id="通义灵码",
    ) is False
    assert (
        infer_source_type(
            source_url="https://developer.aliyun.com/article/1662698",
            official_hosts=official_hosts,
        )
        == "article"
    )


def test_builtin_registry_no_longer_registers_parse_page() -> None:
    registry = ChannelRegistry()
    _register_builtin_channels(registry)
    assert "parse_page" not in registry.list_actions()
    assert {"search_web", "bocha_search", "fetch_url", "extract_structured"}.issubset(
        set(registry.list_actions())
    )


@pytest.mark.asyncio
async def test_bocha_search_channel_with_mocked_httpx(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr(settings, "BOCHA_BASE_URL", "https://api.bochaai.com/v1")
    limiter = _FakeLimiter()
    monkeypatch.setattr("agents.tools.search_bocha._get_bocha_rate_limiter", lambda: limiter)
    response = _FakeBochaResponse(
        {
            "code": 200,
            "data": {
                "webPages": {
                    "value": [
                        {
                            "name": "销售 AI 工具评测",
                            "url": "https://example.cn/sales-ai",
                            "summary": "国内销售团队正在评估 AI 跟进工具。",
                            "snippet": "fallback snippet",
                            "siteName": "Example CN",
                            "datePublished": "2026-06-01",
                        }
                    ]
                }
            },
        }
    )
    fake_client = _FakeBochaAsyncClient(response)
    monkeypatch.setattr("agents.tools.search_bocha.httpx.AsyncClient", lambda **_: fake_client)

    observation = await BochaSearchChannel().invoke(query="销售 AI 工具", max_results=3)

    assert limiter.host == "api.bochaai.com"
    assert fake_client.post_kwargs is not None
    assert fake_client.post_kwargs["url"] == "https://api.bochaai.com/v1/web-search"
    assert observation.result.metadata["provider"] == "bocha"
    assert observation.result.snippets[0].sanitized_text == "国内销售团队正在评估 AI 跟进工具。"
    assert observation.result.snippets[0].metadata["source"] == "bocha_search"


@pytest.mark.asyncio
async def test_bocha_search_channel_classifies_body_quota_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr("agents.tools.search_bocha._get_bocha_rate_limiter", lambda: _FakeLimiter())
    fake_client = _FakeBochaAsyncClient(_FakeBochaResponse({"code": 403, "message": "quota"}))
    monkeypatch.setattr("agents.tools.search_bocha.httpx.AsyncClient", lambda **_: fake_client)

    with pytest.raises(RateLimited):
        await BochaSearchChannel().invoke(query="销售 AI 工具", max_results=3)


@pytest.mark.asyncio
async def test_bocha_search_channel_requires_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", None)

    with pytest.raises(ChannelError, match="BOCHA_API_KEY"):
        await BochaSearchChannel().invoke(query="销售 AI 工具", max_results=3)


@pytest.mark.asyncio
async def test_bocha_search_channel_classifies_official_site_with_competitor_hosts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr("agents.tools.search_bocha._get_bocha_rate_limiter", lambda: _FakeLimiter())
    fake_client = _FakeBochaAsyncClient(
        _FakeBochaResponse(
            {
                "code": 200,
                "data": {
                    "webPages": {
                        "value": [
                            {
                                "name": "Cursor enterprise",
                                "url": "https://cursor.com/enterprise",
                                "summary": "Cursor enterprise deployment page.",
                            }
                        ]
                    }
                },
            }
        )
    )
    monkeypatch.setattr("agents.tools.search_bocha.httpx.AsyncClient", lambda **_: fake_client)

    observation = await BochaSearchChannel().invoke(
        query="cursor enterprise deployment",
        max_results=3,
        competitor_id="Cursor",
    )
    assert observation.result.snippets[0].source_type == "official_site"


@pytest.mark.asyncio
async def test_tavily_forbidden_usage_limit_is_rate_limited(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    monkeypatch.setattr("agents.tools.search_web.TavilyClient", _FakeTavilyQuotaClient)

    with pytest.raises(RateLimited, match="usage limit"):
        await _tavily_search(query="Cursor pricing", max_results=1, country="china")


@pytest.mark.asyncio
async def test_serper_search_channel_with_mocked_httpx(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", "test-serper-key")
    limiter = _FakeLimiter()
    monkeypatch.setattr("agents.tools.search_serper._get_serper_rate_limiter", lambda: limiter)
    response = _FakeBochaResponse(
        {
            "organic": [
                {
                    "title": "Cursor pricing",
                    "link": "https://cursor.com/pricing",
                    "snippet": "Cursor publishes team and enterprise pricing tiers.",
                    "date": "2026-06-01",
                }
            ]
        }
    )
    fake_client = _FakeBochaAsyncClient(response)
    monkeypatch.setattr("agents.tools.search_serper.httpx.AsyncClient", lambda **_: fake_client)

    observation = await SerperSearchChannel().invoke(
        query="cursor pricing", max_results=3, country="china", language="zh"
    )

    assert limiter.host == "google.serper.dev"
    assert fake_client.post_kwargs is not None
    assert fake_client.post_kwargs["url"] == "https://google.serper.dev/search"
    # Tavily-style country name + carrier language are translated to Serper gl/hl codes.
    assert fake_client.post_kwargs["json"]["gl"] == "cn"
    assert fake_client.post_kwargs["json"]["hl"] == "zh-cn"
    assert observation.result.metadata["provider"] == "serper"
    assert observation.result.snippets[0].sanitized_text == (
        "Cursor publishes team and enterprise pricing tiers."
    )
    assert observation.result.snippets[0].metadata["source"] == "serper_search"


@pytest.mark.asyncio
async def test_serper_search_channel_requires_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", None)

    with pytest.raises(ChannelError, match="SERPER_API_KEY"):
        await SerperSearchChannel().invoke(query="cursor pricing", max_results=3)


@pytest.mark.asyncio
async def test_serper_search_channel_classifies_quota_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", "test-serper-key")
    monkeypatch.setattr(
        "agents.tools.search_serper._get_serper_rate_limiter", lambda: _FakeLimiter()
    )
    fake_client = _FakeBochaAsyncClient(
        _FakeSerperHTTPErrorResponse(status_code=403, text="Not enough credits")
    )
    monkeypatch.setattr("agents.tools.search_serper.httpx.AsyncClient", lambda **_: fake_client)

    with pytest.raises(RateLimited):
        await SerperSearchChannel().invoke(query="cursor pricing", max_results=3)


@pytest.mark.asyncio
async def test_bocha_rerank_with_mocked_httpx(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr(settings, "BOCHA_BASE_URL", "https://api.bochaai.com/v1")
    monkeypatch.setattr(settings, "BOCHA_RERANK_MODEL", "gte-rerank")
    limiter = _FakeLimiter()
    monkeypatch.setattr("agents.tools.rerank_bocha._get_bocha_rerank_rate_limiter", lambda: limiter)
    response = _FakeBochaResponse(
        {
            "code": 200,
            "data": {
                "results": [
                    {"index": 1, "relevance_score": 0.91},
                    {"index": 0, "relevance_score": 0.34},
                ]
            },
        }
    )
    fake_client = _FakeBochaAsyncClient(response)
    monkeypatch.setattr("agents.tools.rerank_bocha.httpx.AsyncClient", lambda **_: fake_client)

    ranked = await _request_bocha_rerank(
        query="销售 AI 工具",
        documents=["弱相关", "强相关"],
        top_n=2,
    )

    assert limiter.host == "api.bochaai.com"
    assert fake_client.post_kwargs is not None
    assert fake_client.post_kwargs["url"] == "https://api.bochaai.com/v1/rerank"
    assert ranked == [(1, 0.91), (0, 0.34)]


@pytest.mark.asyncio
async def test_bocha_rerank_preserves_zero_relevance_score(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr(settings, "BOCHA_BASE_URL", "https://api.bochaai.com/v1")
    monkeypatch.setattr("agents.tools.rerank_bocha._get_bocha_rerank_rate_limiter", lambda: _FakeLimiter())
    fake_client = _FakeBochaAsyncClient(
        _FakeBochaResponse(
            {
                "code": 200,
                "data": {
                    "results": [
                        {"index": 0, "relevance_score": 0},
                        {"index": 1, "score": 0.42},
                    ]
                },
            }
        )
    )
    monkeypatch.setattr("agents.tools.rerank_bocha.httpx.AsyncClient", lambda **_: fake_client)

    ranked = await _request_bocha_rerank(
        query="销售 AI 工具",
        documents=["无关", "相关"],
        top_n=2,
    )

    assert ranked == [(0, 0.0), (1, 0.42)]


@pytest.mark.asyncio
async def test_bocha_rerank_classifies_body_quota_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", "test-bocha-key")
    monkeypatch.setattr("agents.tools.rerank_bocha._get_bocha_rerank_rate_limiter", lambda: _FakeLimiter())
    fake_client = _FakeBochaAsyncClient(_FakeBochaResponse({"code": 403, "message": "quota"}))
    monkeypatch.setattr("agents.tools.rerank_bocha.httpx.AsyncClient", lambda **_: fake_client)

    with pytest.raises(RateLimited):
        await _request_bocha_rerank(
            query="销售 AI 工具",
            documents=["a", "b"],
            top_n=2,
        )


@pytest.mark.asyncio
async def test_bocha_rerank_fail_soft_returns_original_order_when_key_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "BOCHA_API_KEY", None)

    ranked = await rerank(
        query="销售 AI 工具",
        documents=["a", "b"],
        top_n=2,
    )

    assert ranked == [(0, None), (1, None)]


@pytest.mark.asyncio
async def test_search_router_queries_both_providers_with_chinese_emphasis() -> None:
    bocha = _FakeSearchChannel(provider="bocha", source_url="https://example.cn/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
        market_scope="中国市场",
    )

    assert observation.channel == "search_web"
    # Breadth: home language (zh→bocha) + global (en→tavily), never excluded by language.
    assert observation.args["providers"] == ["bocha", "tavily"]
    assert observation.args["search_languages"] == ["zh", "en"]
    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 2
    # Emphasis: home (bocha) results lead after merge.
    assert len(observation.result.snippets) == 2
    assert observation.result.snippets[0].source_url == "https://example.cn/a"


@pytest.mark.asyncio
async def test_search_router_degrades_to_tavily_when_bocha_fails() -> None:
    bocha = _FakeSearchChannel(provider="bocha", exc=RateLimited("quota"))
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
        market_scope="中国市场",
    )

    assert observation.args["providers"] == ["tavily"]
    assert observation.args["search_languages"] == ["zh", "en"]
    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 2
    assert {call.get("country") for call in tavily.calls} == {"china", None}


@pytest.mark.asyncio
async def test_search_router_fails_loud_and_fast_when_all_providers_are_rate_limited() -> None:
    bocha = _FakeSearchChannel(provider="bocha", exc=RateLimited("bocha quota"))
    tavily = _FakeSearchChannel(provider="tavily", exc=RateLimited("tavily quota"))
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    with pytest.raises(ChannelError, match="search_web providers failed"):
        await channel.invoke(
            query="销售 AI 工具",
            query_variants=["销售 AI 工具 定价", "销售 AI 工具 口碑"],
            max_results=3,
            response_language="zh",
            market_scope="中国市场",
        )

    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 2
    assert {call.get("country") for call in tavily.calls} == {"china", None}


@pytest.mark.asyncio
async def test_search_router_uses_serper_before_tavily_for_english(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", "test-serper-key")
    serper = _FakeSearchChannel(provider="serper", source_url="https://example.com/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/b")
    channel = SearchWebRouterChannel(serper_channel=serper, tavily_channel=tavily)

    observation = await channel.invoke(
        query="cloud IDE competitors",
        max_results=3,
        response_language="en",
    )

    # Breadth-on mode queries both Serper and Tavily on the same language leg.
    assert observation.args["providers"] == ["serper", "tavily"]
    assert observation.args["search_languages"] == ["en"]
    assert len(serper.calls) == 1
    assert len(tavily.calls) == 1
    # Router hands Serper the carrier language so it can localize hl/gl.
    assert serper.calls[0]["language"] == "en"


@pytest.mark.asyncio
async def test_search_router_degrades_to_tavily_when_serper_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", "test-serper-key")
    serper = _FakeSearchChannel(provider="serper", exc=RateLimited("serper quota"))
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/b")
    channel = SearchWebRouterChannel(serper_channel=serper, tavily_channel=tavily)

    observation = await channel.invoke(
        query="cloud IDE competitors",
        max_results=3,
        response_language="en",
    )

    assert observation.args["providers"] == ["tavily"]
    assert len(serper.calls) == 1
    assert len(tavily.calls) == 1


@pytest.mark.asyncio
async def test_search_router_respects_breadth_toggle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "SERPER_API_KEY", "test-serper-key")
    monkeypatch.setattr(settings, "COLLECTOR_SEARCH_BREADTH_ENABLED", False)
    serper = _FakeSearchChannel(provider="serper", source_url="https://example.com/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/b")
    channel = SearchWebRouterChannel(serper_channel=serper, tavily_channel=tavily)

    observation = await channel.invoke(
        query="cloud IDE competitors",
        max_results=3,
        response_language="en",
    )

    assert observation.args["providers"] == ["serper"]
    assert observation.args["breadth_enabled"] is False
    assert len(serper.calls) == 1
    assert len(tavily.calls) == 0


@pytest.mark.asyncio
async def test_search_router_falls_back_to_tavily_china_for_explicit_zh_when_bocha_key_missing() -> None:
    bocha = _FakeSearchChannel(
        provider="bocha",
        exc=ChannelError("BOCHA_API_KEY is required for bocha_search channel."),
    )
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.cn/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        search_languages=["zh"],
    )

    assert observation.args["providers"] == ["tavily"]
    assert observation.args["search_languages"] == ["zh"]
    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 1
    assert tavily.calls[0]["country"] == "china"
    assert observation.result.metadata["leg_result_counts"] == {
        "zh:bocha": 0,
        "zh:tavily": 1,
    }


@pytest.mark.asyncio
async def test_search_router_runs_query_variants_and_dedupes_across_providers() -> None:
    bocha = _FakeSearchChannel(provider="bocha", source_url="https://example.cn/a?utm=1")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.cn/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="销售 AI 工具",
        query_variants=["销售 AI 工具", "销售 AI 工具 评测"],
        max_results=3,
        response_language="zh",
    )

    assert [call["query"] for call in bocha.calls] == ["销售 AI 工具", "销售 AI 工具 评测"]
    assert [call["query"] for call in tavily.calls] == [
        "销售 AI 工具",
        "销售 AI 工具 评测",
        "销售 AI 工具",
        "销售 AI 工具 评测",
    ]
    # Same canonical URL from both providers collapses to one; home (bocha) copy is kept.
    assert len(observation.result.snippets) == 1
    assert observation.result.snippets[0].source_url == "https://example.cn/a?utm=1"
    assert observation.result.metadata["queries"] == ["销售 AI 工具", "销售 AI 工具 评测"]


@pytest.mark.asyncio
async def test_search_router_adds_market_language_leg_for_japan_scope() -> None:
    # Highlight: an English run about the Japan market also retrieves Japanese-locale sources.
    bocha = _FakeSearchChannel(provider="bocha", source_url="https://example.cn/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="best manga creation tools",
        max_results=3,
        response_language="en",
        market_scope="日本市场",
    )

    assert observation.args["search_languages"] == ["en", "ja"]
    assert bocha.calls == []
    countries = sorted(str(call.get("country")) for call in tavily.calls)
    assert countries == ["None", "japan"]


@pytest.mark.asyncio
async def test_search_router_english_user_china_market_adds_chinese_leg() -> None:
    # Highlight: market language is added regardless of output language — an English user
    # analyzing the China market still searches native Chinese sources via Bocha.
    bocha = _FakeSearchChannel(provider="bocha", source_url="https://example.cn/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="cloud IDE competitors",
        max_results=3,
        response_language="en",
        market_scope="中国大陆市场",
    )

    assert observation.args["search_languages"] == ["en", "zh"]
    # en is home (leads); zh is added from market scope. Both engines are queried.
    assert observation.args["providers"] == ["tavily", "bocha"]
    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 2


@pytest.mark.asyncio
async def test_search_router_honors_explicit_niche_languages() -> None:
    # Highlight: niche languages are reachable by passing search_languages explicitly.
    bocha = _FakeSearchChannel(provider="bocha", source_url="https://example.cn/a")
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    observation = await channel.invoke(
        query="industrial automation vendors",
        max_results=3,
        response_language="en",
        search_languages=["ko", "de"],
    )

    assert observation.args["search_languages"] == ["ko", "de"]
    assert bocha.calls == []
    countries = sorted(str(call.get("country")) for call in tavily.calls)
    assert countries == ["germany", "south korea"]


@pytest.mark.asyncio
async def test_search_router_skips_rate_limited_provider_during_cooldown(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "COLLECTOR_PROVIDER_COOLDOWN_SECONDS", 600)
    bocha = _FakeSearchChannel(provider="bocha", exc=RateLimited("bocha quota"))
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    first = await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
    )
    second = await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
    )

    assert first.args["providers"] == ["tavily"]
    assert second.args["providers"] == ["tavily"]
    assert len(bocha.calls) == 1
    assert len(tavily.calls) == 4
    assert second.result.metadata["leg_result_counts"]["zh:bocha"] == 0


@pytest.mark.asyncio
async def test_search_router_cooldown_disabled_retries_rate_limited_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "COLLECTOR_PROVIDER_COOLDOWN_SECONDS", 0)
    bocha = _FakeSearchChannel(provider="bocha", exc=RateLimited("bocha quota"))
    tavily = _FakeSearchChannel(provider="tavily", source_url="https://example.com/a")
    channel = SearchWebRouterChannel(bocha_channel=bocha, tavily_channel=tavily)

    await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
    )
    await channel.invoke(
        query="销售 AI 工具",
        max_results=3,
        response_language="zh",
    )

    assert len(bocha.calls) == 2
    assert len(tavily.calls) == 4


@pytest.mark.asyncio
async def test_search_web_channel_with_mocked_tavily(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    limiter = _FakeLimiter()
    monkeypatch.setattr("agents.tools.search_web._get_tavily_rate_limiter", lambda: limiter)

    async def _fake_tavily_search(
        query: str,
        *,
        max_results: int,
        country: str | None = None,
    ) -> dict[str, object]:
        del query, max_results
        assert country == "china"
        return {
            "results": [
                {
                    "title": "Cursor pricing update",
                    "url": "https://news.example.com/cursor-pricing",
                    "content": "Cursor updated pricing tiers in public release notes.",
                }
            ]
        }

    monkeypatch.setattr("agents.tools.search_web._tavily_search", _fake_tavily_search)
    channel = TavilySearchChannel()
    observation = await channel.invoke(query="cursor pricing", max_results=3, country="china")
    assert observation.result.snippets
    assert observation.args["country"] == "china"
    assert observation.result.snippets[0].source_type in {"article", "public_review", "pricing_page"}

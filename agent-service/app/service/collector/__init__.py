from service.collector.base import BaseChannel, CollectorObservation, CollectorSnippet, SourceType
from service.collector.errors import (
    ChannelError,
    ChannelNotRegisteredError,
    FetchTimeout,
    RateLimited,
    RobotsBlocked,
)
from service.collector.http_client import CollectorHTTPClient, FetchResponse, get_collector_http_client
from service.collector.rate_limiter import PerHostLimiter
from service.collector.registry import ChannelRegistry, get_channel_registry
from service.collector.robots import RobotsDecision, RobotsGate
from service.collector.source_resolver import (
    SourcePage,
    SourceResolutionResult,
    resolve_official_sources,
)

__all__ = [
    "BaseChannel",
    "ChannelError",
    "ChannelNotRegisteredError",
    "ChannelRegistry",
    "CollectorHTTPClient",
    "CollectorObservation",
    "CollectorSnippet",
    "FetchResponse",
    "FetchTimeout",
    "PerHostLimiter",
    "RateLimited",
    "RobotsBlocked",
    "RobotsDecision",
    "RobotsGate",
    "SourcePage",
    "SourceResolutionResult",
    "SourceType",
    "get_channel_registry",
    "get_collector_http_client",
    "resolve_official_sources",
]

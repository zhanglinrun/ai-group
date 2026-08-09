from __future__ import annotations


class ChannelError(RuntimeError):
    """Raised when a collector channel cannot produce observations."""


class ChannelNotRegisteredError(ChannelError):
    """Raised when channel lookup misses a registered action."""


class RobotsBlocked(ChannelError):
    """Raised when robots.txt disallows a fetch target."""


class FetchTimeout(ChannelError):
    """Raised when upstream fetch exceeds timeout budget."""


class RateLimited(ChannelError):
    """Raised when a provider reports a quota/rate limit (quota exhausted, 429)."""


class RateLimiterTimeout(ChannelError):
    """Raised when the LOCAL per-host limiter queue times out.

    Distinct from RateLimited so a healthy provider is not put in a long cooldown
    just because concurrent in-process callers saturated its per-host token bucket.
    """

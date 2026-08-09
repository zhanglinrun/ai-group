from __future__ import annotations

from collections.abc import Iterable
from functools import lru_cache

from service.collector.base import BaseChannel, CollectorObservation
from service.collector.errors import ChannelNotRegisteredError


class ChannelRegistry:
    def __init__(self) -> None:
        self._channels: dict[str, BaseChannel] = {}

    def register(self, channel: BaseChannel) -> None:
        if not channel.name:
            raise ValueError("ChannelRegistry.register requires channel.name.")
        self._channels[channel.name] = channel

    def register_many(self, channels: Iterable[BaseChannel]) -> None:
        for channel in channels:
            self.register(channel)

    def has(self, action: str) -> bool:
        return action in self._channels

    def get(self, action: str) -> BaseChannel:
        channel = self._channels.get(action)
        if channel is None:
            raise ChannelNotRegisteredError(f"channel not registered: action={action}")
        return channel

    async def invoke(self, action: str, *, args: dict[str, object]) -> CollectorObservation:
        channel = self.get(action)
        return await channel.invoke(**args)

    def list_actions(self) -> list[str]:
        return sorted(self._channels.keys())


def _register_builtin_channels(registry: ChannelRegistry) -> None:
    from agents.tools.extract_structured import ExtractStructuredChannel
    from agents.tools.fetch_url import FetchUrlChannel
    from agents.tools.search_bocha import BochaSearchChannel
    from agents.tools.search_router import SearchWebRouterChannel
    from agents.tools.skill_tools import LoadSkillChannel, ReadSkillFileChannel

    registry.register_many(
        [
            FetchUrlChannel(),
            SearchWebRouterChannel(),
            BochaSearchChannel(),
            ExtractStructuredChannel(),
            LoadSkillChannel(),
            ReadSkillFileChannel(),
        ]
    )


@lru_cache
def get_channel_registry() -> ChannelRegistry:
    registry = ChannelRegistry()
    _register_builtin_channels(registry)
    return registry

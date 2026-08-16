from __future__ import annotations

import sys
from types import ModuleType

from core.config import Settings
from core.nacos_discovery import NacosRegistration


def _install_fake_nacos(monkeypatch, client_cls) -> None:
    fake = ModuleType("nacos")
    fake.NacosClient = client_cls
    monkeypatch.setitem(sys.modules, "nacos", fake)


def test_registration_skips_when_disabled(monkeypatch) -> None:
    class Boom:
        def __init__(self, *args, **kwargs) -> None:
            raise AssertionError("should not connect")

    _install_fake_nacos(monkeypatch, Boom)
    settings = Settings(NACOS_DISCOVERY_ENABLED=False, NACOS_SERVER_ADDR="nacos:8848")
    registration = NacosRegistration(settings)
    registration.start()
    assert registration._client is None


def test_registration_logs_and_survives_client_errors(monkeypatch) -> None:
    class Boom:
        def __init__(self, *args, **kwargs) -> None:
            raise RuntimeError("nacos down")

    _install_fake_nacos(monkeypatch, Boom)
    settings = Settings(NACOS_DISCOVERY_ENABLED=True, NACOS_SERVER_ADDR="nacos:8848", APP_PORT=8090)
    registration = NacosRegistration(settings)
    registration.start()
    assert registration._client is None


def test_registration_adds_and_removes_instance(monkeypatch) -> None:
    calls: list[tuple[str, tuple, dict]] = []

    class FakeClient:
        def __init__(self, *args, **kwargs) -> None:
            calls.append(("init", args, kwargs))

        def add_naming_instance(self, *args, **kwargs) -> None:
            calls.append(("add", args, kwargs))

        def send_heartbeat(self, *args, **kwargs) -> None:
            calls.append(("beat", args, kwargs))

        def remove_naming_instance(self, *args, **kwargs) -> None:
            calls.append(("remove", args, kwargs))

    _install_fake_nacos(monkeypatch, FakeClient)
    settings = Settings(
        NACOS_DISCOVERY_ENABLED=True,
        NACOS_SERVER_ADDR="nacos:8848",
        NACOS_SERVICE_NAME="agent-service",
        NACOS_IP="10.0.0.8",
        APP_PORT=8090,
        NACOS_HEARTBEAT_INTERVAL_SECONDS=60,
    )
    registration = NacosRegistration(settings)
    registration.start()
    registration.stop()
    assert any(name == "add" for name, _, _ in calls)
    assert any(name == "remove" for name, _, _ in calls)
    add_call = next(item for item in calls if item[0] == "add")
    assert add_call[1][0] == "agent-service"
    assert add_call[1][1] == "10.0.0.8"
    assert add_call[1][2] == 8090

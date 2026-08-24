from __future__ import annotations

import socket
import threading
import time
from typing import Any

from core.config import Settings
from utils.logger import get_logger

log = get_logger("nacos_discovery")

_active: NacosRegistration | None = None


def active_registration() -> NacosRegistration | None:
    return _active


def lookup_member_base_url(settings: Settings) -> str:
    fallback = (settings.MEMBER_SERVICE_URL or "").rstrip("/")
    registration = _active
    if registration is None:
        return fallback
    discovered = registration.lookup_base_url(settings.MEMBER_SERVICE_NACOS_NAME)
    return discovered or fallback


def _local_ip() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


class NacosRegistration:
    """Registers the Agent as a Nacos naming instance. Failures are logged, not fatal."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: Any = None
        self._ip = (settings.NACOS_IP or "").strip() or _local_ip()
        self._port = settings.APP_PORT
        self._service_name = settings.NACOS_SERVICE_NAME
        self._stop = threading.Event()
        self._heartbeat: threading.Thread | None = None

    @property
    def enabled(self) -> bool:
        return bool(self._settings.NACOS_DISCOVERY_ENABLED and (self._settings.NACOS_SERVER_ADDR or "").strip())

    def start(self) -> None:
        if not self.enabled:
            return
        try:
            from nacos import NacosClient

            self._client = NacosClient(
                self._settings.NACOS_SERVER_ADDR,
                namespace=self._settings.NACOS_NAMESPACE,
                username=self._settings.NACOS_USERNAME or None,
                password=self._settings.NACOS_PASSWORD or None,
            )
            self._client.add_naming_instance(
                self._service_name,
                self._ip,
                self._port,
                ephemeral=True,
            )
            self._heartbeat = threading.Thread(
                target=self._heartbeat_loop, name="nacos-heartbeat", daemon=True
            )
            self._heartbeat.start()
            global _active
            _active = self
            log.info(
                "nacos.registered",
                service=self._service_name,
                ip=self._ip,
                port=self._port,
            )
        except Exception:
            log.exception("nacos.register_failed", service=self._service_name)

    def lookup_base_url(self, service_name: str) -> str | None:
        if self._client is None or not (service_name or "").strip():
            return None
        try:
            payload = self._client.list_naming_instance(service_name, healthy_only=True)
        except Exception:
            log.exception("nacos.lookup_failed", service=service_name)
            return None
        hosts: list[Any] = []
        if isinstance(payload, dict):
            raw_hosts = payload.get("hosts") or []
            if isinstance(raw_hosts, list):
                hosts = raw_hosts
        elif isinstance(payload, list):
            hosts = payload
        for host in hosts:
            if not isinstance(host, dict):
                continue
            if host.get("healthy") is False or host.get("enabled") is False:
                continue
            ip = str(host.get("ip") or "").strip()
            port = host.get("port")
            if not ip or port is None:
                continue
            return f"http://{ip}:{int(port)}"
        return None

    def stop(self) -> None:
        global _active
        if _active is self:
            _active = None
        self._stop.set()
        if self._client is None:
            return
        try:
            self._client.remove_naming_instance(self._service_name, self._ip, self._port)
            log.info("nacos.deregistered", service=self._service_name)
        except Exception:
            log.exception("nacos.deregister_failed", service=self._service_name)

    def _heartbeat_loop(self) -> None:
        interval = max(1, self._settings.NACOS_HEARTBEAT_INTERVAL_SECONDS)
        while not self._stop.wait(interval):
            try:
                if self._client is not None:
                    self._client.send_heartbeat(self._service_name, self._ip, self._port)
            except Exception:
                log.exception("nacos.heartbeat_failed", service=self._service_name)

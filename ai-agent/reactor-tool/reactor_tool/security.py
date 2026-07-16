# -*- coding: utf-8 -*-
"""reactor-tool 服务边界安全配置。"""

from __future__ import annotations

import os
import secrets
from dataclasses import dataclass

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response


_LOCAL_ENVIRONMENTS = {"local", "dev", "development", "test"}
_DEFAULT_CORS_ORIGINS = (
    "http://localhost:5173",
    "http://127.0.0.1:5173",
)


@dataclass(frozen=True)
class ReactorToolSecuritySettings:
    """启动时冻结的服务安全设置，避免单次请求被环境变量漂移影响。"""

    environment: str
    token: str
    cors_origins: tuple[str, ...]

    @property
    def is_local(self) -> bool:
        return self.environment in _LOCAL_ENVIRONMENTS


def load_security_settings() -> ReactorToolSecuritySettings:
    """读取并校验安全配置；非本地环境缺少令牌时拒绝启动。"""
    environment = (
        os.getenv("REACTOR_TOOL_ENV")
        or os.getenv("ENV")
        or "local"
    ).strip().lower()
    token = (
        os.getenv("REACTOR_TOOL_TOKEN")
        or os.getenv("AI_GROUP_INTERNAL_TOKEN")
        or ""
    ).strip()
    if not token and environment not in _LOCAL_ENVIRONMENTS:
        raise RuntimeError(
            "REACTOR_TOOL_TOKEN or AI_GROUP_INTERNAL_TOKEN is required outside local development"
        )

    configured_origins = os.getenv("REACTOR_TOOL_CORS_ORIGINS", "")
    cors_origins = tuple(
        origin.strip().rstrip("/")
        for origin in configured_origins.split(",")
        if origin.strip()
    ) or _DEFAULT_CORS_ORIGINS
    if "*" in cors_origins:
        raise RuntimeError("REACTOR_TOOL_CORS_ORIGINS must be an explicit allowlist")

    return ReactorToolSecuritySettings(
        environment=environment,
        token=token,
        cors_origins=cors_origins,
    )


def validate_bind_address(host: str, settings: ReactorToolSecuritySettings):
    """即使标记为 local，只要显式监听非 loopback，也必须配置令牌。"""
    normalized_host = (host or "").strip().lower().strip("[]")
    if normalized_host not in {"127.0.0.1", "::1", "localhost"} and not settings.token:
        raise RuntimeError("a reactor-tool token is required when binding beyond loopback")


class InternalToolTokenMiddleware(BaseHTTPMiddleware):
    """只保护高危工具 API，保留文件预览/下载和签名存储 URL 的兼容性。"""

    def __init__(self, app, settings: ReactorToolSecuritySettings):
        super().__init__(app)
        self._token = settings.token

    async def dispatch(
        self,
        request: Request,
        call_next: RequestResponseEndpoint,
    ) -> Response:
        if not _requires_internal_token(request):
            return await call_next(request)

        # 本地 loopback 开发可以显式不配置 token；一旦配置，所有工具请求都必须认证。
        if not self._token:
            return await call_next(request)

        supplied_token = _extract_token(request)
        if not supplied_token or not secrets.compare_digest(supplied_token, self._token):
            return JSONResponse(
                status_code=401,
                content={
                    "code": "REACTOR_TOOL_UNAUTHORIZED",
                    "message": "missing or invalid reactor-tool token",
                },
                headers={"WWW-Authenticate": "Bearer"},
            )
        return await call_next(request)


def _requires_internal_token(request: Request) -> bool:
    path = request.url.path
    if path.startswith("/v1/tool/") or path.startswith("/v1/documents/"):
        return True
    # Preview/download remain public URLs; file mutation and content lookup stay internal.
    return path.startswith("/v1/file_tool/") and request.method.upper() not in {"GET", "HEAD", "OPTIONS"}


def _extract_token(request: Request) -> str:
    tool_token = request.headers.get("X-Tool-Token", "").strip()
    if tool_token:
        return tool_token

    authorization = request.headers.get("Authorization", "").strip()
    scheme, separator, credentials = authorization.partition(" ")
    if separator and scheme.lower() == "bearer":
        return credentials.strip()
    return ""

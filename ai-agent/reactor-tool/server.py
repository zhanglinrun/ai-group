# -*- coding: utf-8 -*-
# =====================
# 
# 
# Author: liumin.423
# Date:   2025/7/7
# =====================
import os
import warnings
from contextlib import asynccontextmanager
from optparse import OptionParser
from pathlib import Path

import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI
from loguru import logger
from starlette.middleware.cors import CORSMiddleware

from reactor_tool.security import (
    InternalToolTokenMiddleware,
    ReactorToolSecuritySettings,
    load_security_settings,
    validate_bind_address,
)
from reactor_tool.util.middleware_util import UnknownException, HTTPProcessTimeMiddleware

load_dotenv()

# 压掉已知的第三方库噪音告警，避免排查真实异常时被无关 warning 干扰。
warnings.filterwarnings(
    "ignore",
    message="pkg_resources is deprecated as an API.*",
    category=UserWarning,
)


def print_logo():
    from pyfiglet import Figlet
    f = Figlet(font="slant")
    print(f.renderText("Reactor Tool"))


def log_setting():
    log_path = os.getenv("LOG_PATH", Path(__file__).resolve().parent / "logs" / "server.log")
    log_format = "{time:YYYY-MM-DD HH:mm:ss.SSS} {level} {module}.{function} {message}"
    logger.add(log_path, format=log_format, rotation="200 MB")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Initialize process-scoped resources using FastAPI's current lifecycle API."""
    log_setting()
    print_logo()
    yield


def create_app() -> FastAPI:
    security_settings = load_security_settings()
    _app = FastAPI(lifespan=lifespan)

    register_middleware(_app, security_settings)
    register_router(_app)

    @_app.get("/health", include_in_schema=False)
    async def health():
        return {"status": "UP"}

    return _app

def register_middleware(app: FastAPI, security_settings: ReactorToolSecuritySettings):
    app.add_middleware(UnknownException)
    app.add_middleware(InternalToolTokenMiddleware, settings=security_settings)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(security_settings.cors_origins),
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Accept", "Authorization", "Content-Type", "X-Tool-Token"],
        allow_credentials=True,
    )
    app.add_middleware(HTTPProcessTimeMiddleware)


def register_router(app: FastAPI):
    from reactor_tool.api import api_router
    app.include_router(api_router)


app = create_app()


if __name__ == "__main__":
    parser = OptionParser()
    parser.add_option(
        "--host",
        dest="host",
        type="string",
        default=os.getenv("REACTOR_TOOL_HOST", "127.0.0.1"),
    )
    parser.add_option(
        "--port",
        dest="port",
        type="int",
        default=int(os.getenv("REACTOR_TOOL_PORT", "1601")),
    )
    parser.add_option(
        "--workers",
        dest="workers",
        type="int",
        default=int(os.getenv("REACTOR_TOOL_WORKERS", "1")),
    )
    (options, args) = parser.parse_args()

    print(f"Start params: {options}")

    validate_bind_address(options.host, load_security_settings())

    reload_enabled = os.getenv("REACTOR_TOOL_RELOAD", "false").strip().lower() == "true"

    # 单进程时直接传入 app 实例，避免复制环境后再被子进程/重载器放大解释器差异。
    if not reload_enabled and int(options.workers) <= 1:
        uvicorn.run(
            app=app,
            host=options.host,
            port=options.port,
            timeout_keep_alive=99999,
            ws_ping_interval=99999,
            ws_ping_timeout=99999,
        )
    else:
        uvicorn.run(
            app="server:app",
            host=options.host,
            port=options.port,
            workers=options.workers,
            reload=reload_enabled,
            timeout_worker_healthcheck=60,
            timeout_keep_alive=99999,
            ws_ping_interval=99999,
            ws_ping_timeout=99999,
        )

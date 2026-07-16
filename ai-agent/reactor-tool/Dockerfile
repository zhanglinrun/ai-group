FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_NO_PROGRESS=1 \
    PATH=/app/.venv/bin:$PATH

RUN groupadd --gid 10001 reactor && \
    useradd --uid 10001 --gid reactor --home-dir /app --no-create-home reactor

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN pip install --no-cache-dir uv && uv sync --frozen --no-dev --no-install-project

COPY reactor_tool ./reactor_tool
COPY server.py ./server.py
RUN uv sync --frozen --no-dev && \
    mkdir -p /data/files /workspaces && \
    chown -R reactor:reactor /app /data /workspaces

USER reactor

ENV REACTOR_TOOL_ENV=prod \
    REACTOR_TOOL_HOST=0.0.0.0 \
    REACTOR_TOOL_WORKERS=1 \
    SKILL_ALLOWED_RUNTIMES=python \
    SKILL_WORKSPACE_ROOT=/workspaces \
    FILE_SAVE_PATH=/data/files \
    SQLITE_DB_PATH=/data/autobots.db \
    LOG_PATH=/data/server.log \
    HOME=/tmp

EXPOSE 1601

CMD ["sh", "-c", "python -m reactor_tool.db.db_engine && exec python server.py --workers 1"]

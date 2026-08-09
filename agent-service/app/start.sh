#!/usr/bin/env bash
set -euo pipefail

until python -c "import os, socket; socket.create_connection((os.getenv('POSTGRES_HOST', 'agent-postgres'), int(os.getenv('POSTGRES_PORT', '5432'))), timeout=2)" 2>/dev/null; do
  echo "waiting for postgres ..."
  sleep 2
done

alembic upgrade head
exec "$@"

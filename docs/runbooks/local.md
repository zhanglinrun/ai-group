# 本地运行手册

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build
```

健康检查顺序：MySQL/Redis/Postgres → Nacos/RabbitMQ → Java 服务 → Agent Alembic → Web。Agent 若无法连接 Member，会将 Run 标记为待对账，不会直接超额扣费。

启动后可执行 `powershell -ExecutionPolicy Bypass -File eval/http-smoke.ps1`，验证 Gateway 健康、匿名访问拦截和 Auth 路由转发。

停止环境：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml down
```

如需清空本地数据，确认没有重要演示数据后再执行 `down -v`。

如果本机曾经用另一组 `MYSQL_ROOT_PASSWORD` 初始化过同名 Compose volume，修改 `.env` 不会改变 MySQL 已保存的 root 密码；备份演示数据后执行一次 `down -v`，再用当前 `.env` 重新初始化即可。

# DevOps 运行资产

这里集中放置本地基础设施和部署入口，不属于任何业务服务：

- `compose/`：完整环境、开发环境和 Java/Python/Web 镜像构建文件。
- `mysql/`：数据库初始化入口；业务 SQL 仍归属对应的 `group-service`、`pay-service`。
- `xxl-job/`：Admin 种子 SQL（含 auth/pay/group/member 执行器）。
- `observability/`：ELK + Prometheus / Grafana + SkyWalking 独立 Compose 与配置，详见 [observability/README.md](observability/README.md)。
- `jmeter/`：Gateway / Group / Member HTTP 压测脚本，详见 [jmeter/README.md](jmeter/README.md)。

中间件配置写在 Compose 里，没有单独的 `postgres/`、`redis/`、`kafka/` 目录。

默认 `docker-compose.full.yml` 优先保证秋招演示可以稳定启动，包含 MySQL、Redis、Kafka、Nacos、Postgres、Java 服务、Agent 和前端；观测组件在 `observability/docker-compose.observability.yml` 中**按需单独启动**，避免把 Elasticsearch / SkyWalking 等高内存组件强制带入每次开发启动。

```powershell
# 业务栈
cd dev-ops/compose && docker compose -f docker-compose.full.yml up -d
# 观测栈（需业务栈已创建 xiongdoctor_default 网络）
cd ../observability && docker compose -f docker-compose.observability.yml up -d
# 可选：启用 SkyWalking Java Agent 后重建 Java 服务
cd ../compose
docker compose --env-file ../observability/skywalking-agents.env -f docker-compose.full.yml up -d --build `
  gateway-service auth-service member-service group-service pay-service
# 可选：JMeter 压测（宿主机需安装 JMeter）
powershell -ExecutionPolicy Bypass -File ../jmeter/run-login-group-order.ps1
```

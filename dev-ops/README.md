# DevOps 运行资产

这里集中放置本地基础设施和部署入口，不属于任何业务服务：

- `compose/`：完整环境、开发环境和 Java/Python/Web 镜像构建文件。
- `mysql/`：数据库初始化入口；业务 SQL 仍归属对应的 `group-service`、`pay-service`。
- `xxl-job/`：Admin 种子 SQL（含 auth/pay/group/member 执行器）。
- `observability/`：ELK（Elasticsearch / Logstash / Kibana）+ Prometheus / Grafana 独立 Compose 与配置，详见 [observability/README.md](observability/README.md)。

中间件配置写在 Compose 里，没有单独的 `postgres/`、`redis/`、`kafka/`、`minio/` 目录。MinIO 已从 full 栈移除，不要讲成文件中台。

默认 `docker-compose.full.yml` 优先保证秋招演示可以稳定启动，包含 MySQL、Redis、Kafka、Nacos、Postgres、Java 服务、Agent 和前端；观测组件在 `observability/docker-compose.observability.yml` 中**按需单独启动**，避免把 Elasticsearch 等高内存组件强制带入每次开发启动。

```powershell
# 业务栈
cd dev-ops/compose && docker compose -f docker-compose.full.yml up -d
# 观测栈（需业务栈已创建 xiongdoctor_default 网络）
cd dev-ops/observability && docker compose -f docker-compose.observability.yml up -d
```

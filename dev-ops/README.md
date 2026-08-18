# DevOps 运行资产

这里集中放置本地基础设施和部署入口，不属于任何业务服务：

- `compose/`：完整环境、开发环境和 Java/Python/Web 镜像构建文件。
- `mysql/`：数据库初始化入口；业务 SQL 仍归属对应的 `group-service`、`pay-service`。
- `xxl-job/`：Admin 种子 SQL（含 auth/pay/group/member 执行器）。
- `observability/`：Prometheus/Grafana/ELK 的采集和仪表盘配置说明。

中间件配置写在 Compose 里，没有单独的 `postgres/`、`redis/`、`kafka/`、`minio/` 目录。MinIO 已从 full 栈移除，不要讲成文件中台。

默认 `docker-compose.full.yml` 优先保证秋招演示可以稳定启动，包含 MySQL、Redis、Kafka、Nacos、Postgres、Java 服务、Agent 和前端；观测组件作为独立部署单元，避免把 Elasticsearch 等高内存组件强制带入每次开发启动。

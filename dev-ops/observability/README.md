# 可观测性

Java 服务暴露 Spring Boot Actuator 的 `health`、`info` 和 `prometheus` 指标，Agent 暴露应用健康和运行指标。生产部署时在本目录加入 Prometheus scrape 配置、Grafana dashboard 和 Logstash pipeline；日志统一通过 JSON 输出，业务服务不直接依赖 ELK 才能启动。

这一区域与业务源码隔离，新增观测组件时只需要更新 DevOps Compose/Helm 配置，不把 Elasticsearch、Logstash 或 Grafana SDK 引入业务模块。

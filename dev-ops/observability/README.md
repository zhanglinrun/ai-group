# 可观测性（ELK + Prometheus / Grafana）

与业务栈解耦，按需启动。参考 `group-pay/group-buy/docs/dev-ops` 的 ELK / Grafana 布局，适配本仓库多服务结构。

## 组件

| 组件 | 端口 | 说明 |
|------|------|------|
| Elasticsearch | 9200 | 日志索引 |
| Logstash | 4560 (TCP) | 接收 LogstashEncoder JSON 日志 |
| Kibana | 5601 | 日志检索 |
| Prometheus | 9090 | 指标采集 |
| Grafana | 3000 | 监控面板（默认 admin / admin） |
| Grafana MCP | 8000 | 可选，`--profile mcp` 启动 |

## 启动顺序

### 1. 启动业务栈（必须先有 `xiongdoctor_default` 网络）

```powershell
cd dev-ops/compose
docker compose -f docker-compose.full.yml up -d
```

### 2. 启动观测栈

```powershell
cd dev-ops/observability
docker compose -f docker-compose.observability.yml up -d
```

可选 Grafana MCP（需先在 Grafana 创建 API Key 并写入环境变量 `GRAFANA_API_KEY`）：

```powershell
docker compose -f docker-compose.observability.yml --profile mcp up -d
```

## ELK 日志接入

`group-service` 已集成 `logstash-logback-encoder`。默认 **dev** 不上报 ELK；启用 **elk** profile 后 TCP 上报 Logstash。

### Docker 全栈 + ELK

```powershell
cd dev-ops/compose
$env:LOGSTASH_HOST = "logstash"
$env:GROUP_SPRING_PROFILES_ACTIVE = "dev,elk"
docker compose -f docker-compose.full.yml up -d group-service
```

### 本地 IDE 跑 group-service + Docker ELK

`application-dev.yml` 中 `logstash.host` 默认 `127.0.0.1`，本地启动时加 profile：

```text
spring.profiles.active=dev,elk
```

### Kibana 验证

1. 打开 http://localhost:5601  
2. **Stack Management → Index Patterns**，创建 `ai-group-log-*`  
3. **Discover** 查看日志；或 ES：`GET _cat/indices?v` 确认 `ai-group-log-YYYY.MM.dd` 存在

## Prometheus 抓取

`prometheus/prometheus.yml` 含两套 target：

- **ai-group-docker**：`gateway-service:8080`、`group-service:8091`（与 full 栈同网段）
- **ai-group-host**：`host.docker.internal:8080/8091`（IDE 或已映射端口时）

当前已接入 Micrometer Prometheus 的服务：**gateway-service**、**group-service**。

验证：

- http://localhost:9090/targets  
- http://localhost:8080/actuator/prometheus（Gateway）  
- http://localhost:8091/actuator/prometheus（Group，需映射端口或走 Docker 网段）

如需从宿主机抓 group 指标，可在 full compose 启动时设置：

```powershell
$env:GROUP_METRICS_PUBLISH_PORT = "8091"
docker compose -f docker-compose.full.yml up -d group-service
```

## Grafana

1. http://localhost:3000 ，登录 `admin` / `admin`  
2. 已自动配置 Prometheus 数据源（`http://prometheus:9090`）  
3. 可导入 Spring Boot / JVM 官方 Dashboard（如 ID `4701`、`11378`）

## AI MCP（可选）

- **日志**：Kibana / Elasticsearch MCP，按 `trace-id`、订单号检索  
- **监控**：`grafana-mcp` + `GRAFANA_API_KEY`，在 Cursor 中分析 QPS、JVM

## 资源说明

Elasticsearch 默认 `-Xms512m -Xmx512m`。内存不足时可调小或单独关闭 ELK，仅保留 Prometheus/Grafana（需拆分 compose 或手动 `docker compose up prometheus grafana`）。

## 文件结构

```text
dev-ops/observability/
├── docker-compose.observability.yml
├── logstash/logstash.conf
├── kibana/config/kibana.yml
├── prometheus/prometheus.yml
└── grafana/provisioning/datasources/datasource.yml
```

业务模块 **不依赖** ELK/Prometheus 才能启动；观测栈挂掉不影响交易链路。

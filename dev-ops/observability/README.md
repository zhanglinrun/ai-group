# 可观测性（ELK + Prometheus / Grafana + SkyWalking）

与业务栈解耦，按需启动。参考 `group-pay/group-buy/docs/dev-ops` 的 ELK / Grafana 布局，适配本仓库多服务结构；SkyWalking 与 JMeter 压测入口一并纳入本目录说明。

## 组件

| 组件 | 端口 | 说明 |
|------|------|------|
| Elasticsearch | 9200 | 日志索引 + SkyWalking 存储 |
| Logstash | 4560 (TCP) | 接收 LogstashEncoder JSON 日志 |
| Kibana | 5601 | 日志检索 |
| Prometheus | 9090 | 指标采集 |
| Grafana | 3000 | 监控面板（默认 admin / admin） |
| Grafana MCP | 8000 | 可选，`--profile mcp` 启动 |
| SkyWalking OAP | 11800 / 12800 | APM 接收（gRPC / HTTP） |
| SkyWalking UI | 8088 | 拓扑、链路、端点分析 |

压测脚本在 [`../jmeter/`](../jmeter/README.md)，不随 Compose 启动。

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

### 3.（可选）启用 SkyWalking Java Agent

镜像已内置 agent，默认不激活。观测栈起来后，用 env-file 重建 Java 服务：

```powershell
cd dev-ops/compose
docker compose --env-file ../observability/skywalking-agents.env -f docker-compose.full.yml up -d --build `
  gateway-service auth-service member-service group-service pay-service
```

验证：打开 http://localhost:8088 ，等几分钟流量后应能看到各服务与拓扑。Agent 连 `skywalking-oap:11800`（同一 Docker 网络）。

IDE 本地跑 Java 时，自行下载 [SkyWalking Java Agent 9.3+](https://skywalking.apache.org/downloads/)，启动参数示例：

```text
-javaagent:/path/to/skywalking-agent.jar
-Dskywalking.agent.service_name=group-service
-Dskywalking.collector.backend_service=127.0.0.1:11800
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

## JMeter 压测

见 [`../jmeter/README.md`](../jmeter/README.md)。典型入口：

```powershell
powershell -ExecutionPolicy Bypass -File dev-ops/jmeter/run-login-group-order.ps1
```

压测时可对照 Grafana 与 SkyWalking UI 观察 QPS、延迟与跨服务链路。

## AI MCP（可选）

- **日志**：Kibana / Elasticsearch MCP，按 `trace-id`、订单号检索  
- **监控**：`grafana-mcp` + `GRAFANA_API_KEY`，在 Cursor 中分析 QPS、JVM

## 资源说明

Elasticsearch 默认 `-Xms512m -Xmx512m`，同时承载 ELK 与 SkyWalking 存储。内存不足时可：

- 只起 Prometheus/Grafana：`docker compose up -d prometheus grafana`
- 或关掉 ELK / SkyWalking 中的一组：`docker compose stop elasticsearch logstash kibana skywalking-oap skywalking-ui`

## 文件结构

```text
dev-ops/observability/
├── docker-compose.observability.yml
├── skywalking-agents.env
├── logstash/logstash.conf
├── kibana/config/kibana.yml
├── prometheus/prometheus.yml
└── grafana/provisioning/datasources/datasource.yml

dev-ops/jmeter/                  # 压测（宿主机 JMeter，不进 Compose）
├── plans/
├── reports/                     # gitignore
└── run-*.ps1
```

业务模块 **不依赖** ELK / Prometheus / SkyWalking 才能启动；观测栈挂掉不影响交易链路。Agent（Python）走 LangSmith，不挂 SkyWalking Java Agent。

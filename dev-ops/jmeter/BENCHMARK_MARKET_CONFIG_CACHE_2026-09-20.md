# 营销配置 + 大厅缓存压测记录（2026-09-20）

## 口径（对齐 campus-dash S3 / Cache Aside）

与 `campus-dash` 详情缓存一致的核心能力：

| 能力 | 本项目 |
|---|---|
| 缓存层 | **仅 Redis**（无进程内 Caffeine/Map） |
| 读路径 | 营销配置 + **大厅详情**（活动统计 / 进行中团池 / 本人团列表） |
| 写失效 | 锁单 / 结算 / 退款 **afterCommit** 删缓存 + 延迟双删 |
| 配置 TTL | 10min + 抖动；大厅短 TTL 5s 兜底 |
| 座位库存 | **不进缓存**，仍 Redis Lua + MySQL |

脚本：`dev-ops/jmeter/plans/bench_market_config_cache.py`  
探针：`market_config_cache_probe`（单次请求串行打 config + stats + progressPool + ownerTeams）

**主对照档与 campus-dash S3 同构：50 并发 / 5000 请求。**

## 主验收轮（50 并发 / 5000 请求，对齐 campus-dash S3）

runId：`20260920T111642039394Z`

| 项 | cache-off | cache-on | campus-dash S3 |
|---|---:|---:|---:|
| 成功 / 异常 | 5000 / 0 | 5000 / 0 | 5000 档 |
| 吞吐 | 156.42 rps | **1152.85 rps**（约 **7.4×**） | （文档未主报） |
| 平均 RTT | 317.11 ms | **42.54 ms** | — |
| P99 RTT | 1062.20 ms | **92.12 ms** | 117→**54 ms** |
| hitRate | 0% | **99.7%** | **97.5%** |
| redisHitRate | — | **99.99%** | — |
| dbLoadCount | **20000** | **4** | 5000→123 |
| 回源降幅 | — | **99.98%**（20000→4） | **97.5%**（5000→123） |

### 简历口径（推荐，与 campus 同档可比）

缓存一致性与大厅读优化：针对活动配置与大厅详情热点，构建以 MySQL 为权威、Redis Cache Aside 的读写体系，叠加分片 TTL、空值防穿透、Single-Flight 与延迟双删。50 并发下 5000 次请求 0 异常，缓存命中率 99.7%，详情回源 20000→4 次（降约 100%），P99 1062→92ms，吞吐约 7.4×（156→1153 rps）。

更短版：构建 MySQL/Redis 两方数据一致体系，Cache Aside 加固大厅+配置读路径，命中率 99.7%、回源降约 100%（20000→4）、P99 1062→92ms。

### 与 campus-dash 对照结论

同负载（50/5000）下：命中率与回源降幅 **不低于** 对方（99.7% / ≈100% vs 97.5% / 97.5%）；P99 绝对值略高（92 vs 54ms），因本探针单次含 **4 路** Redis/业务键，campus 为单任务详情键。

## 加压轮（300 并发 / 15820 请求）

runId：`20260920T110857399927Z` — 含 Tomcat 排队，不可与 campus 50 档比绝对值。

| 项 | cache-off | cache-on |
|---|---:|---:|
| 吞吐 | 118.51 rps | **1023.29 rps** |
| P99 | 5441.66 ms | **1131.15 ms** |
| hitRate | 0% | **81.5%**（键级 99.2%） |
| dbLoad | 63280 | **761**（降 98.8%） |

大厅 5s TTL + 本人团按 user 分键，长窗压测会有过期回源，请求级全键命中低于 50/5000 档。

## 复现

```powershell
docker compose -p ai-group-bench --env-file .env `
  -f dev-ops/compose/docker-compose.full.yml `
  -f dev-ops/jmeter/bench-compose.override.yml up -d --build group-service

# 主对照（对齐 campus-dash S3）
python dev-ops/jmeter/plans/bench_market_config_cache.py --concurrency 50 --requests 5000 --warmup 500

# 加压轮
python dev-ops/jmeter/plans/bench_market_config_cache.py --concurrency 300 --requests 15820 --warmup 800
```

## 边界

- 读集 3 个商品 + 并发数个 userId；50/5000 短窗内大厅 TTL 几乎不过期，故 dbLoadOn≈4。
- 键级 `redisHitRate` 含空值命中；请求级 hit 要求探针内全部键均命中。
- 300 档 P99 含 Tomcat 排队；与 campus-dash 50 档不可直接比绝对值。
- 锁单数字见 [BENCHMARK_GROUP_LOCK_2026-09-20.md](BENCHMARK_GROUP_LOCK_2026-09-20.md)。

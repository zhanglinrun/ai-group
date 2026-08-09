# 简历项目表述

- 设计并实现 Java 21 深度调研 Agent：STANDARD/DEEP 共用 Agent Harness，DEEP 基于 Spring AI Alibaba Graph 实现 Fan-out/Fan-in、checkpoint、Evidence Ledger 和 Citation Gate；P170 产品 E2E 覆盖文档、PPT、HTML、CSV 产物。
- 建立可恢复执行控制面：Run/Event Ledger、SSE `Last-Event-ID` 回放、durable tool attempt、lease/fencing、outbox/reconcile 与幂等额度结算；非幂等 crash-window 显式进入 UNKNOWN。
- 接入 Member 两阶段额度账户，实现 Agent 调用 reserve/confirm/release 的 requestId+traceId 关联；不改动冻结的拼团/支付源码，仅通过额度契约集成。
- 建立 Trace/Eval/安全证据：OTLP 白名单投影、Java Golden Dataset hash、失败 Trace 回链、Python sandbox/SSRF/MCP 红队测试。

不要在简历中写未经固定测试集、模型、配置和日期验证的成功率、性能、成本或 exactly-once。

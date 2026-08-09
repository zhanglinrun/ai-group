# 数据库策略

- Java 交易域：MySQL，Auth/Member/Group/Pay 使用独立数据库，服务不跨库读表。
- Agent 运行域：Postgres，Run、Checkpoint、Evidence、LLM Call 和价格快照由 Alembic 管理。
- Redis：会话、锁、计数和短缓存，不承担账本事实。
- RabbitMQ：Outbox 事件总线；统一事件 envelope 见 `contracts/events/`。

积分流水只追加不更新，冻结/确认/释放通过 `(user_id, request_id)` 和冻结号幂等。金额、Token 费用和积分均使用整数微单位，模型价格版本写入每条 LLM Call。

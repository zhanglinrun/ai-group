# 熊博士服务合同

`openapi/` 和 `events/` 是跨服务通信的唯一来源。Java、Python 和前端都只能围绕这些合同实现适配器，不在 BFF 或 Controller 中通过动态 Map 继续扩散隐式字段。

规则：

- 外部 HTTP 接口使用 OpenAPI 3.1。
- MQ 事件使用 JSON Schema，事件必须包含 `eventId`、`eventType`、`schemaVersion`、`aggregateId`、`traceId` 和 `occurredAt`。
- 生成的 SDK 只写入构建目录，不提交到仓库。
- 破坏性变更递增 `schemaVersion`，旧版本至少保留一个发布周期。


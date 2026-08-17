# ai-group 服务合同

`openapi/` 和 `events/` 是手写对照稿，不是 codegen 唯一来源。`scripts/validate-contracts.ps1` 只检查文件存在、JSON 可解析且不少于 50 字节。实现以各服务 DTO / Pydantic / 前端 types 为准；不要在 BFF 或 Controller 里继续用动态 Map 扩散隐式字段。

规则：

- 外部 HTTP 接口使用 OpenAPI 3.1。
- MQ 事件使用 JSON Schema，事件必须包含 `eventId`、`eventType`、`schemaVersion`、`aggregateId`、`traceId` 和 `occurredAt`。
- 生成的 SDK 只写入构建目录，不提交到仓库。
- 破坏性变更递增 `schemaVersion`，旧版本至少保留一个发布周期。


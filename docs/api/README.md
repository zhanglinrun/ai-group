# API 约定

`contracts/openapi/*.yaml` 是唯一接口来源。浏览器只调用 Gateway 下的 `/api/auth`、`/api/bff`、`/api/member`、`/api/group` 和 `/api/pay`；Agent 地址不写入前端环境变量。

所有异步响应带 `run_id` 或 `order_id`，写操作必须携带业务幂等键。错误响应保留稳定的 `error_code`，BFF 的降级字段只表示依赖不可用，不伪造成功。

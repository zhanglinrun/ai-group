# AI-Group 双账号交易与 Agent 浏览器验收（2026-07-18）

## 验收目标

使用本地应用内浏览器和当前开发服务验证同一条可演示主链路：

`注册 A → 开团 → A 支付后等待参团 → 注册 B → B 加入同一团队并支付 → 显式封团 → 双方权益到账 → DEEP Agent 工具任务`

本地支付仅替代支付宝资金动作；订单状态、pay→group 结算登记、group 封团、成团通知、pay outbox、RabbitMQ
和 member 权益入账仍走真实业务实现。演示入口只在 `dev` profile 且
`AI_GROUP_DEMO_PAYMENT_ENABLED=true` 时注册，并继续校验 Gateway 身份与订单归属。

## 浏览器结果

| 场景 | 结果 | 可见证据 |
| --- | --- | --- |
| 注册与免费额度 | 通过 | 两个全新账号注册后均显示 5 点月度免费额度、0 点永久额度 |
| A 自己开团 | 通过 | 轻享额度包生成独立待支付订单 |
| A 分阶段支付 | 通过 | 点击“模拟支付并等待参团”后订单显示“已支付，待封团”，拼团大厅仍显示该团队 `1/10`、进行中 |
| B 加入同一团队 | 通过 | B 的拼团大厅显示 A 的团队，并可点击“立即参团 ¥12”生成第二笔订单 |
| B 支付与封团 | 通过 | B 先进入“已支付，待封团”，再点击“第二个成员支付后，封团并结算” |
| 双方权益到账 | 通过 | A、B 的订单均显示“额度已到账”，可用额度 65 点，其中永久付费额度 60 点、待成团 0 单 |
| DEEP Agent Loop | 通过 | 页面展示 2/2 Todo、`todo_write`、`code_interpreter` 工具证据、verification card 和成功终态 |
| Agent 结果 | 通过 | 计算 `1² + ... + 1000² = 333833500`，并与 `n(n+1)(2n+1)/6` 交叉验证一致 |
| Agent 元数据 | 通过 | 页面显示 `qwen-plus`、18,759 tokens、23.4s，并生成 Python 与 Markdown artifact |

两人未达到 3 人奖励档，因此每人按当前已达档位获得 60 点基础额度，没有虚构 3 人团加赠。

## 自动化回归

`docs/dev-ops/verify-e2e.ps1` 已改为复现相同双用户状态机：

1. 注册并登录两个隔离账号；
2. A 开团并调用 `demo_mark_paid`，断言返回 `GROUP_WAITING` 且团队仍在大厅；
3. B 使用同一 `teamId` 参团并调用 `demo_mark_paid`；
4. B 调用 `demo_finalize_group`，断言返回 `GROUP_FINALIZED`；
5. 轮询两个额度账户，确认双方分别增加 60,000,000 microcredits；
6. 确认已封团队伍不再出现在活动团队列表。

本轮执行结果：

```text
E2E OK (A=0->60000000, B=0->60000000 microcredits)
```

该脚本不再使用 SQL 把订单直接改成 `PAY_SUCCESS`，也不再伪造 `group_buy_notify`，因此能够发现“首个成员支付后
团队被过早关闭”这类跨服务状态机错误。

## 修复内容

- 新增 `POST /api/v1/alipay/demo_mark_paid`：只推进正常支付成功服务并登记 group 成员已支付，不封团；
- 新增 `POST /api/v1/alipay/demo_finalize_group`：只允许订单 owner 对已支付拼团订单显式封团；
- 重复 `PAY_SUCCESS` 的 mark-paid 会重试幂等 settlement 通知，增强临时网络失败后的演示恢复能力；
- 支付弹窗区分直购与拼团：拼团先“支付并等待参团”，再显式“封团并结算”；
- 交易提示改用应用上下文中的 Ant Design message 实例，消除动态主题下的静态 message 警告。

## 当前回归证据

- 支付：84 tests，0 failures，0 errors；新增分阶段支付、owner 校验、直购隔离、重复通知和支付后封团测试；
- 前端：47 test files、192 tests 全部通过；ESLint 与生产构建通过；
- Agent：既有 508 tests 全部通过，真实浏览器 DEEP 工具任务成功；
- Python 工具：148 passed、1 skipped、3 subtests passed；
- `start-full-stack.ps1` 使用当前源码完成全量构建、启动与健康检查。

## 边界

- 本地演示没有产生真实支付宝资金流，不能写成“支付宝沙箱双人实付”；
- 两人封团用于在十分钟内展示状态机和权益闭环，正式活动仍可等待目标人数或超时策略；
- 单次浏览器成功证明当前环境链路可运行，不等价于生产 SLA、线上客户量或长期 Agent 任务成功率。

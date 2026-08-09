# 熊博士秋招面试材料

## 一句话

熊博士是由 Member 积分驱动的研究工作台：Java 负责 Gateway、认证、拼团支付和积分账本，Python LangGraph 负责研究编排、证据、报告、Trace 和 Token 计费。

## 面试主线

1. 用户通过 HttpOnly Cookie 登录，Gateway 用 Sa-Token 校验并注入短期 HMAC 身份信封。
2. 用户在拼团大厅购买积分，Pay 只负责现金订单和支付宝沙箱；Group 只负责拼团状态、库存和折扣；Member 是积分账本唯一权威。
3. 创建 Agent Run 时冻结积分，LangGraph 每次调用记录 Token 和价格版本，结束时确认实际消耗并释放余量。
4. BFF 只聚合页面 DTO 和透传 SSE，浏览器不能直接访问 Python Agent。

## 为什么 Java + Python

Java 服务处理登录、订单、库存、支付回调、幂等和并发控制，利用成熟的 Spring Cloud、MySQL 事务、Redis 原子操作和消息补偿；Python 服务处理 LangGraph 节点、模型 SDK、异步研究和快速迭代的 Agent 逻辑。两边通过 OpenAPI、事件 Schema 和 HMAC 身份协议协作，而不是共享数据库或 SDK。

## 证据入口

- [现场演示脚本](demo.md)
- [简历措辞](resume.md)
- [高频问答](questions.md)
- [架构说明](../architecture/README.md)

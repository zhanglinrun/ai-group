# 熊博士目标架构

Java 侧负责身份、入口、拼团、现金支付和积分账本；Python 侧只负责 LangGraph 研究工作流、证据、报告和 Token 使用计量。浏览器只能访问 Gateway，Agent 由 BFF 通过内部 HTTP 调用。

`onlyForStudy` 的应用服务、状态机、分布式锁、事务消息和补偿思想被保留；Dubbo、Seata/TCC 等 Java 专属组件不跨语言复制。Sa-Token 是 Java 会话权威，Gateway 校验后使用短期 HMAC 身份上下文交给 Python。


# 高频问答

1. **为什么 Graph 不是 Loop？** Graph 表达 DEEP 的分支和路由；Harness 统一调用语义，避免图节点绕过账本和额度。
2. **为什么不直接用通用 Agent？** 通用抽象无法替代本项目的 quota、SSE、ledger、CompletionGate 和业务身份边界。
3. **Function Calling 与 MCP 的区别？** 前者是模型到本地工具的 schema 调用；后者是受治理的外部工具协议，需额外做发现、版本、权限和出站限制。
4. **Skill 如何防供应链注入？** descriptor 先行、正文按需加载、hash 固定、路径沙箱和信任标记。
5. **Memory 与 Context 如何分层？** Context 是本次运行的预算投影；长期记忆必须显式确认、隔离、可删除。
6. **为什么不宣称 exactly-once？** 外部副作用和网络故障存在不确定窗口；系统用 idempotency、fencing 和 UNKNOWN 诚实表达。
7. **Tool crash-window 怎么处理？** 持久 attempt、lease、callback 和 reconcile；只对安全副作用重试。
8. **SSE 断线如何恢复？** Run 不绑定浏览器连接，事件持久化；客户端以 `Last-Event-ID` owner-scoped replay。
9. **为什么 Python 不放 LLM？** 保持数据面确定性、可控权限和更小攻击面；模型调用集中在 Java Harness。
10. **Eval 如何避免“看起来成功”？** 固定 dataset hash、规则合同、trial、报告与失败 Trace 绑定。
11. **Trace 为什么不保存隐藏推理？** 观测只需运行元数据；保存敏感 prompt/推理会扩大隐私和注入风险。
12. **为什么不修改 Group/Pay？** 它们是冻结业务域；Member 额度已是足够且可审计的集成契约。

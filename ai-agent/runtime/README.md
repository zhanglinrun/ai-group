# runtime

Agent 运行时资产目录（与 Java 六模块并列）：

| 子目录 | 作用 |
| --- | --- |
| `skills/` | Skills 技能包，由 Java Agent 从磁盘加载 |
| `tools/` | 工具执行服务（HTTP `:1601`），承接搜索、抓页、代码、报告、生图等重能力 |

启动工具服务：

```powershell
cd runtime/tools
uv sync
.\start.ps1
```

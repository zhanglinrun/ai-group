# Reactor Tool

`python >= 3.11`

## 项目结构

```
.
├── reactor_tool
│   ├── api                             # api 服务
│   ├── model                           # 协议和 DataClass
│   ├── prompt                          # Prompt 仓库
│   ├── tool                            # 工具执行逻辑
│   └── util                            # 工具类
├── .env_template                       # 环境变量
├── server.py                           # FastAPI 服务启动
└── start.sh                            # 启动脚本

```

## 项目启动

python 环境和依赖安装  
```bash
pip install uv
cd reactor-tool
uv sync
source .venv/bin/activate
```

首次启动，需要初始化数据库（后续不再需要）
```bash

cd reactor-tool

python -m reactor_tool.db.db_engine
```

启动服务
```bash

cd reactor-tool

cp .env.example .env
# 至少填写 AI_GROUP_INTERNAL_TOKEN 与实际使用的模型 Key；完整可选项参考 .env_template

./start.sh
```

Windows 推荐启动方式
```powershell
cd reactor-tool
.\\start.ps1
```

## 内置 MCP STDIO 服务

`reactor-tool` 同时提供两个由官方 Python MCP SDK 实现的独立进程服务，Java Agent 会从
`ai_client_tool_mcp` 动态发现并通过 STDIO 调用：

| Server | Tools | 边界 |
| --- | --- | --- |
| `project-knowledge` | `project_search_knowledge`、`project_get_flow` | 只读查询模块旁随仓库提交的固定 JSON 语料，不接受文件路径 |
| `agent-utility` | `utility_estimate_llm_quota`、`utility_explain_quota_formula` | 只做有界整数微额度计算，不访问网络、文件或进程 |

两个服务的参数 schema 不暴露任意路径、URL 或命令，单次 UTF-8 结果限制为 8 KiB。它们的目的
是提供可复现的跨语言 MCP 演示；纯计算本身并不必须使用 MCP。第三方 `npx/uvx` Server 不会
默认启用，因为 STDIO 子进程会继承 Agent 环境变量，未经审计的服务可能接触模型密钥。

真实协议测试会分别启动两个子进程并执行 `initialize -> tools/list -> tools/call`：

```powershell
$ErrorActionPreference = 'Stop'
uv sync --frozen
uv run --frozen pytest tests/test_mcp_servers.py -q
```

Java/Python 互操作测试从 `ai-agent` 目录执行：

```powershell
$ErrorActionPreference = 'Stop'
mvn -pl ai-agent-app -am -Pmcp-stdio-it `
  '-Dtest=McpStdioInteropTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

说明：

- 如果你这个环境是从其他项目复制过来的，或者此前在别的项目里激活过虚拟环境，直接用 `uv run python server.py` 可能出现 `VIRTUAL_ENV does not match the project environment path .venv` 的 warning。
- 这类 warning 一般不是业务失败的根因，但它说明当前 shell 上下文被别的项目污染了。
- `start.ps1` / `start.sh` 会主动清理外部 `VIRTUAL_ENV`，并强制使用当前项目自己的 `.venv`、单进程模式启动。
- 启动脚本会把本地文件落盘目录设置为 `FILE_SAVE_PATH=skilloutput`，同时保留 `FILE_SERVER_URL=http://127.0.0.1:1601/v1/file_tool` 作为前端可访问的 HTTP 文件服务地址。
- 不要把 `FILE_SERVER_URL` 配置成本地磁盘目录，否则前端拿到的 `domainUrl/downloadUrl` 会变成不可访问路径，文件组件点击后将无法预览。
- 图片生成工具依赖 `IMAGE_GENERATION_BASE_URL`、`IMAGE_GENERATION_API_KEY`、`IMAGE_GENERATION_MODEL`；如果和通用 LLM 走同一个 OpenAI 兼容网关，可以在 `.env` 里直接映射到 `OPENAI_*`。

## 安全边界

- 服务默认只监听 `127.0.0.1`；只有容器内部才显式监听 `0.0.0.0`，Compose 仍仅发布到宿主机 loopback。
- `/v1/tool/*` 全部请求，以及当前均使用 POST 的 `/v1/documents/*` 和 `/v1/file_tool/*` 非安全方法，接受并要求 `X-Tool-Token` 或 `Authorization: Bearer <token>`。令牌优先读取 `REACTOR_TOOL_TOKEN`，否则复用 `AI_GROUP_INTERNAL_TOKEN`。`prod` 等非本地环境缺少令牌会拒绝启动；本地未配置令牌时允许 loopback 调试。
- CORS 默认仅允许 `http://localhost:5173` 与 `http://127.0.0.1:5173`，可用逗号分隔的 `REACTOR_TOOL_CORS_ORIGINS` 覆盖，不接受 `*`。
- 文件预览/下载 GET、HEAD 与带自身签名的 `/v1/storage/download/.../{token}` 保持匿名可访问，因此不要求把内部令牌暴露给浏览器；文档/文件写请求由 Vite 开发代理只在服务端代理层注入令牌。
- Skill 默认只允许 `python`。确需 Node/shell/PowerShell/BAT 时必须通过 `SKILL_ALLOWED_RUNTIMES` 显式开放；生产环境不建议开放宿主 shell。
- 脚本在一次性临时目录中运行，使用最小环境变量集合，并受最大超时、并发数、输出字节数、生成文件数/大小限制。POSIX 还设置 CPU、地址空间、文件大小和打开文件数上限；容器配置额外限制内存、CPU、PID、Linux capabilities 和提权。

关键配置示例见 `.env.example`。本地启动后可检查：

```powershell
$headers = @{ 'X-Tool-Token' = $env:AI_GROUP_INTERNAL_TOKEN }
Invoke-RestMethod -Uri 'http://127.0.0.1:1601/health'
Invoke-WebRequest -Method Post -Uri 'http://127.0.0.1:1601/v1/tool/script_runner' -Headers $headers -ContentType 'application/json' -Body '{}'
```

上述限制是应用层防线，不等价于完整 OS 沙箱。要执行不可信代码，应使用 Docker/微虚机等隔离运行时，并限制出站网络。仓库提供的 `docker-compose.yml` 可作为本地隔离基线；容器化 Skill 调用时，Java 与容器必须约定相同的 `/skills` 路径。

容器启动：

```powershell
Copy-Item .env.example .env
# 修改 .env：至少替换 AI_GROUP_INTERNAL_TOKEN，并填写实际需要的模型 Key
docker compose up --build
```

Compose 会在容器启动时幂等初始化 `/data/autobots.db`，因此干净数据卷不需要额外执行建表命令。

## DeepSearch 说明

- Query 分解与 `extend/search/report` 三阶段 SSE 协议保持不变。
- DeepSearch 的 LLM 调用支持独立配置 `DEEPSEARCH_BASE_URL`、`DEEPSEARCH_API_KEY`；留空时自动回退到 `OPENAI_BASE_URL`、`OPENAI_API_KEY`。
- 默认搜索提供方已切换为 `DuckDuckGo`，通过 `USE_SEARCH_ENGINE=ddg` 启用。
- 页面正文优先通过 `Jina Reader` 抓取，失败时会自动回退到原始 HTTP 页面解析。
- 可通过 `DDG_REGION`、`DDG_SAFESEARCH`、`JINA_API_KEY`、`JINA_READER_TIMEOUT` 调整抓取行为。
- Java 侧 `deep_search` 调用、数据库持久化与前端历史回放展示无需额外改造。

## Web Fetch 说明

- `POST /v1/tool/web_fetch` 用于抓取单个 `http://` 或 `https://` URL，不负责搜索、批量抓取或浏览器渲染。
- HTML 页面优先使用 `trafilatura` 输出 Markdown，提取失败时自动回退到 `BeautifulSoup.get_text()`；`raw.githubusercontent.com`、`r.jina.ai` 这类返回 Markdown/纯文本的地址会直接按文本内容落盘。
- 每次成功抓取都会强制把完整正文保存为 Markdown 文件产物，并通过 `fileInfo` 返回；内联 `data.content` 仅用于摘要展示，过长时会被截断。
- 文件名优先使用网页标题生成，标题缺失时回退到 URL slug，便于 Java 侧 artifact 落账和后续历史复用。

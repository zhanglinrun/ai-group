# ai-group 前端

> 项目入口见根目录 [`README.md`](../README.md)。页面上的产品品牌仍是熊博士。

## Scope

该目录负责 ai-group 前端应用：公开区（营销、价格与拼团）与工作区（分析执行、证据、报告和 Trace）。

## Routes

| Group | Routes |
|---|---|
| Public | `/`, `/examples`, `/pricing`, `/share/:runId` |
| Workspace | `/app`, `/app/runs/new`, `/app/runs/:runId`, `/app/watch`, `/app/settings` |
| Commerce | `/group-buy`, `/account`, `/orders`, `/checkout/:orderId`, `/payment/result`, `/admin` |

## Development

```bash
cd frontend
npm ci
npm run dev
```

默认地址：`http://localhost:5173`。

## API Configuration

本地开发默认走 **Vite 代理**：前端请求 `/api/*` 由 `vite.config.ts` 转发到 Gateway `http://localhost:8080`，无需配置 CORS。

- 环境变量：无需设置，开发代理统一指向 Gateway。
- Python Agent 地址只存在于 Gateway 路由配置，浏览器不会直连 Agent。

```bash
# 默认：留空，走 Vite /api 代理
VITE_API_BASE_URL=
```

## Scripts

```bash
npm run dev
npm run build
npm run preview
npm run type-check
```

## Notes

- 所有页面通过路由 `lazy()` 加载，减少首屏负担。
- 全局错误与请求失败通过 ErrorBoundary + Toaster 统一处理。
- 运行事件通过 SSE 分发到 report/metrics/trace 等 query cache。

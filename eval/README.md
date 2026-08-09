# 黑盒验收入口

`eval` 只通过 Gateway 的公开地址验证系统边界，不导入任何服务的 Java/Python 类，也不读取服务数据库。它适合秋招演示前的快速回归和 Docker Compose 启动后的第一轮检查。

## 使用

先在仓库根目录启动完整环境：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build -d
```

再运行：

```powershell
powershell -ExecutionPolicy Bypass -File eval/http-smoke.ps1
```

脚本会优先读取进程环境变量，其次读取仓库根目录 `.env` 的 `AI_GROUP_INTERNAL_TOKEN`。

可通过参数检查其他地址或内部管理令牌：

```powershell
powershell -ExecutionPolicy Bypass -File eval/http-smoke.ps1 `
  -BaseUrl http://localhost:8080 `
  -InternalToken $env:AI_GROUP_INTERNAL_TOKEN
```

脚本覆盖三件事：Gateway 管理端点需要内部令牌、受保护业务接口不能匿名访问、公开登录路由已经被 Gateway 转发。注册、支付回调幂等、拼团并发和 Agent Token 结算属于需要测试数据的场景，分别由各服务测试和 Compose 验收流程覆盖。

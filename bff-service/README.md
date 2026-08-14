# `bff-service`（前端聚合层）

这是 `ai-group`（平台层）里专门服务前端的一层。`BFF`（Backend for Frontend，服务于前端的后端）的作用是：前端一个页面往往需要好几个服务的数据，与其让前端挨个去调、再自己拼，不如由这一层在服务端一次性聚合好，再返回给前端。

它跑在端口 `8083`，用 `OpenFeign`（声明式服务调用）去调 member、group、pay 三个服务。

---

## 为什么要有这一层

拿定价页举个例子：页面要展示「额度包 + 每个套餐对应的拼团价」。但这两块数据分在两个服务里——套餐列表在 member，拼团价格和活动在 group。

如果没有 BFF，前端就得先调 member 拿套餐，再逐个调 group 查拼团价，然后自己把价格对应回每个套餐。逻辑一复杂，前端就很难维护。BFF 把这些拼装活挪到服务端，前端只调一个接口就够了。

---

## 对外接口

都在 `/api/bff` 下：

| 接口 | 聚合了什么 |
| --- | --- |
| `GET /api/bff/pricing` | 额度包（member）+ 每个套餐各自的拼团营销配置（group），把拼团价拼回到每个套餐上 |
| `GET /api/bff/group-buy/{activityId}` | 按活动 ID 反查对应拼团商品，返回该活动自己的队伍与价格 |
| `GET /api/bff/account/summary` | 额度摘要（member）+ 用户待成团的拼团订单 |
| `GET /api/bff/orders` | 用户订单列表（pay） |

---

## 一个值得注意的设计：降级

BFF 里有个 `DegradeContext`（降级上下文）。它的作用是：如果某个下游服务暂时不可用（比如拼团服务查不到），BFF 不会让整个页面直接报错，而是把能拿到的数据先返回，同时在返回结果的 `meta` 里标记「哪一块降级了」。

这样前端至少能把可用的部分先渲染出来，而不是整页白屏。把这种「部分失败也要能出页面」的容错逻辑放在服务端统一处理，前端就不用自己操心每个下游挂了该怎么办。

---

## 和其他服务的关系

- 调 `member`（额度服务）：套餐列表、额度摘要。
- 调 `group`（拼团服务）：拼团营销配置、进行中的队伍。
- 调 `pay`（支付服务）：用户订单列表。

调 member 走 `Nacos`（注册中心）服务发现，调 group / pay 默认按配置的地址直连（本地可配 URL）。对应代码在 `MemberFeignClient`、`GroupFeignClient`、`PayFeignClient`。

身份方面，BFF 自己不校验登录——请求经过网关时已经校验过了。BFF 用共享库里的 `GatewayUserContextFilter` 验 `X-Internal-Jwt` 后绑定用户，再把入站 JWT **原样转发**给下游（`FeignAuthForwardConfig` / Agent 代理），不按 ThreadLocal 重造 userId。

---

## 本地运行

依赖 `Nacos`（注册中心），以及它聚合的 member / group / pay 三个服务。

跟平台一套启动脚本走：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File docs/dev-ops/start-full-stack.ps1
```

单独跑（先确认 Nacos 和下游服务就绪）：

```bash
cd bff-service && mvn spring-boot:run
```

---

## 相关代码

- `BffController`（聚合接口）：四个聚合接口和拼装逻辑。
- `MemberFeignClient` / `GroupFeignClient` / `PayFeignClient`：三个下游调用客户端。
- `FeignAuthForwardConfig`（Feign 身份透传配置）：调下游时带上当前用户身份。
- `BffSecurityConfig`（安全配置）：装配网关身份过滤器。

---

## 提醒

- BFF 只做聚合和裁剪，不放核心业务规则。真正的业务判断留在各自的服务里，否则这层会慢慢变成又一个「什么都管」的大服务。
- 聚合多个下游时要考虑某个下游挂掉的情况，沿用现有的降级写法，别让一个下游拖垮整个接口。

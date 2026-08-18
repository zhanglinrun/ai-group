import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { platformClient } from "@/platform/client";

interface ActivityRule {
  activityId: number;
  activityName?: string;
  goodsName?: string;
  originalPrice?: number | string;
  groupPayPrice?: number | string;
  marketPlan?: string;
  marketExpr?: string;
  target?: number;
  status?: number;
}

const PLAN_OPTIONS = [
  { value: "ZJ", label: "直减", example: "5" },
  { value: "MJ", label: "满减", example: "100,10" },
  { value: "ZK", label: "折扣", example: "0.85" },
  { value: "N", label: "N 元购", example: "49" },
] as const;

export function AdminPage(): JSX.Element {
  const cards = [
    ["数据总览", "查看注册用户、进行中拼团、支付订单和 Agent 运行概况。", "/app"],
    ["商品 SKU", "管理额度包、价格、赠送积分和上下架状态。", "/group-buy"],
    ["拼团活动", "管理活动目标人数、库存和倒计时。", "/group-buy"],
    ["订单与支付", "查看现金订单、支付宝回调、关单和补偿状态。", "/account"],
    ["退款与补偿", "处理未成团退款、支付对账和失败重试。", "/account"],
    ["用户与角色", "核对用户状态和管理员权限，操作均经过 Gateway。", "/app/settings"],
    ["积分账户", "核对冻结、确认、释放和权益发放幂等结果。", "/account"],
    ["模型价格", "维护 Token 价格版本，历史账单保留原始版本。", "/app/settings"],
    ["Agent Run", "查看任务状态、Token 消耗、失败重试和报告质量。", "/app"],
  ];
  const [activities, setActivities] = useState<ActivityRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<number | null>(null);
  const [ruleMessage, setRuleMessage] = useState<string | null>(null);

  async function loadActivities(): Promise<void> {
    setLoading(true);
    try {
      const response = await platformClient.get<{ data?: ActivityRule[] }>("/api/group/admin/activities");
      setActivities(response.data.data ?? []);
      setRuleMessage(null);
    } catch (error) {
      setRuleMessage(error instanceof Error ? error.message : "营销规则读取失败，请检查管理员权限");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadActivities(); }, []);

  function patchActivity(activityId: number, patch: Partial<ActivityRule>): void {
    setActivities((current) => current.map((activity) => activity.activityId === activityId ? { ...activity, ...patch } : activity));
  }

  async function saveRule(activity: ActivityRule): Promise<void> {
    setSavingId(activity.activityId);
    setRuleMessage(null);
    try {
      await platformClient.put(`/api/group/admin/activities/${activity.activityId}`, {
        marketPlan: activity.marketPlan,
        marketExpr: activity.marketExpr,
        target: Math.max(2, Math.min(100, Number(activity.target ?? 3) || 3)),
      });
      setRuleMessage(`${activity.activityName ?? activity.activityId} 的营销规则已保存并立即生效。`);
      await loadActivities();
    } catch (error) {
      setRuleMessage(error instanceof Error ? error.message : "营销规则保存失败");
    } finally {
      setSavingId(null);
    }
  }

  return <div className="space-y-6">
    <div><p className="text-sm font-medium text-primary">运营控制台</p><h1 className="mt-2 text-3xl font-semibold">熊博士管理中心</h1><p className="mt-2 text-sm text-foreground-muted">所有操作都通过 Java Gateway 权限校验，状态来自各领域服务的真实数据。</p></div>
    <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">{cards.map(([title, text, href]) => <Card key={title}><CardHeader><CardTitle>{title}</CardTitle></CardHeader><CardContent><p className="text-sm text-foreground-muted">{text}</p><Link className="mt-4 inline-block text-sm font-medium text-primary hover:underline" to={href}>进入模块 →</Link></CardContent></Card>)}</div>
    <Card>
      <CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><div><CardTitle>营销规则配置</CardTitle><p className="mt-1 text-sm text-foreground-muted">为每个拼团活动设置成团人数和直减、满减、折扣或 N 元购。</p></div><Button variant="outline" onClick={() => void loadActivities()} disabled={loading}>{loading ? "读取中…" : "刷新规则"}</Button></div></CardHeader>
      <CardContent className="space-y-4">
        {ruleMessage ? <p className="rounded-md border border-primary/20 bg-primary/5 px-3 py-2 text-sm text-primary">{ruleMessage}</p> : null}
        {loading ? <p className="py-6 text-center text-sm text-foreground-muted">正在读取活动规则…</p> : null}
        {!loading && activities.length === 0 ? <p className="py-6 text-center text-sm text-foreground-muted">暂无可配置的拼团活动。</p> : null}
        {activities.map((activity) => {
          const selectedPlan = PLAN_OPTIONS.find((option) => option.value === activity.marketPlan) ?? PLAN_OPTIONS[0];
          return <div key={activity.activityId} className="rounded-xl border border-border p-4">
            <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-medium">{activity.activityName ?? `活动 ${activity.activityId}`}</p><p className="mt-1 text-xs text-foreground-muted">{activity.goodsName ?? "额度包"} · 原价 ¥{Number(activity.originalPrice ?? 0).toFixed(2)} · 当前拼团价 ¥{Number(activity.groupPayPrice ?? 0).toFixed(2)}</p></div><span className="rounded-full bg-secondary px-2 py-1 text-xs text-foreground-muted">活动 {activity.activityId}</span></div>
            <div className="mt-4 grid gap-3 md:grid-cols-[140px_180px_1fr_auto] md:items-end">
              <label className="space-y-1 text-sm"><span className="text-foreground-muted">成团人数</span><Input min={2} max={100} type="number" value={activity.target ?? 3} onChange={(event) => patchActivity(activity.activityId, { target: Number(event.target.value) || 3 })} /></label>
              <label className="space-y-1 text-sm"><span className="text-foreground-muted">优惠类型</span><select className="h-10 w-full rounded-md border border-border bg-background px-3 text-sm" value={activity.marketPlan ?? "ZJ"} onChange={(event) => patchActivity(activity.activityId, { marketPlan: event.target.value })}>{PLAN_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}（{option.value}）</option>)}</select></label>
              <label className="space-y-1 text-sm"><span className="text-foreground-muted">规则表达式（{selectedPlan.example}）</span><Input value={activity.marketExpr ?? ""} onChange={(event) => patchActivity(activity.activityId, { marketExpr: event.target.value })} placeholder={selectedPlan.example} /></label>
              <Button onClick={() => void saveRule(activity)} disabled={savingId === activity.activityId}>{savingId === activity.activityId ? "保存中…" : "保存规则"}</Button>
            </div>
            <p className="mt-2 text-xs text-foreground-subtle">默认 3 人成团，可按活动单独调整（2～100 人）。示例：直减填写 5；满减填写 100,10（满 100 减 10）；折扣填写 0.85；N 元购填写 49。</p>
          </div>;
        })}
      </CardContent>
    </Card>
  </div>;
}

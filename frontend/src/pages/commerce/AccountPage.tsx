import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { accountOverview, type QuotaSummary } from "@/platform/client";

export function AccountPage(): JSX.Element {
  const [summary, setSummary] = useState<QuotaSummary & { quotaLedger?: Array<Record<string, unknown>> }>({});
  useEffect(() => { accountOverview().then(setSummary).catch(() => undefined); }, []);
  const available = summary.availableQuota ?? ((summary.freeQuotaBalance || 0) + (summary.paidQuotaBalance || 0) - (summary.frozenBalance || 0));
  return <div className="space-y-6"><div><p className="text-sm font-medium text-primary">账户中心</p><h1 className="mt-2 text-3xl font-semibold">积分与订单</h1><p className="mt-2 text-sm text-foreground-muted">这里展示拼团入账、Agent 预留以及按 Token 用量结算后的积分账本。</p></div><div className="grid gap-4 md:grid-cols-4"><Metric title="可用积分" value={formatCredits(available)} accent /><Metric title="赠送积分" value={formatCredits(summary.freeQuotaBalance)} /><Metric title="购买积分" value={formatCredits(summary.paidQuotaBalance)} /><Metric title="冻结中" value={formatCredits(summary.frozenBalance)} /></div><Card><CardContent className="p-4 text-sm text-foreground-muted"><p className="font-medium text-foreground">Token 计费规则</p><p className="mt-1">输入每百万 Token 5 积分，输出每百万 Token 30 积分。系统按实际 Token 精确结算，不按 1K Token 向上取整；拼团或直购获得的是积分额度。</p><p className="mt-1 text-xs text-foreground-subtle">1 积分 = 1,000,000 微积分；运行中的预留积分只是冻结上限，结算后会释放未使用部分。</p></CardContent></Card><Card><CardHeader><CardTitle>最近流水</CardTitle></CardHeader><CardContent>{summary.quotaLedger?.length ? <div className="divide-y divide-border">{summary.quotaLedger.slice(0, 12).map((entry, index) => <div className="flex items-center justify-between py-3 text-sm" key={`${String(entry.id || index)}`}><span>{String(entry.remark || entry.type || "积分变动")}</span><span className="font-medium">{formatCredits(Number(entry.amount ?? entry.deltaMicroPoints ?? 0))}</span></div>)}</div> : <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-foreground-muted">暂时没有流水，先去参加一次拼团吧。</div>}</CardContent></Card><Button asChild><Link to="/group-buy">去积分商城</Link></Button></div>;
}

function formatCredits(value: number | undefined): string {
  return `${((value ?? 0) / 1_000_000).toFixed(2)} 积分`;
}

function Metric({ title, value, accent = false }: { title: string; value: string; accent?: boolean }): JSX.Element {
  return <Card><CardContent className="p-4"><p className="text-sm text-foreground-muted">{title}</p><p className={accent ? "mt-2 text-2xl font-semibold text-primary" : "mt-2 text-2xl font-semibold"}>{value}</p></CardContent></Card>;
}

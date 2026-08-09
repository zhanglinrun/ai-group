import { Check, ShoppingCart, Zap } from "lucide-react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const PACKAGES = [
  { name: "轻享额度包", quota: 60, price: 12 },
  { name: "标准额度包", quota: 300, price: 60, featured: true },
  { name: "大额额度包", quota: 700, price: 140 },
];

export function PricingPage(): JSX.Element {
  return <section className="space-y-10 py-8">
    <header className="text-center"><p className="text-sm font-medium text-primary">Token 计费</p><h1 className="mt-2 text-h1 text-foreground">按用量购买额度</h1><p className="mt-2 text-caption text-foreground-muted">输入每百万 Token 5 积分、输出 30 积分；直购立即到账，拼团支付后等待成团，实际用量会在账单中明细展示。</p></header>
    <div className="grid gap-6 md:grid-cols-3">{PACKAGES.map((item) => <Card key={item.name} className={item.featured ? "border-primary/50 shadow-raised" : ""}><CardHeader><div className="flex items-center justify-between"><CardTitle>{item.name}</CardTitle>{item.featured ? <span className="rounded-full bg-primary/10 px-2 py-1 text-xs font-medium text-primary">推荐</span> : null}</div><p className="mt-1 text-sm text-foreground-muted">{item.quota.toLocaleString()} 积分额度 · 按 Token 用量扣减 · 永久有效</p></CardHeader><CardContent><p className="text-3xl font-semibold">¥{item.price.toFixed(2)}</p><ul className="mt-4 space-y-2 text-sm text-foreground-muted"><li className="flex gap-2"><Check className="h-4 w-4 text-success" />直购支付后立即入账</li><li className="flex gap-2"><Check className="h-4 w-4 text-success" />可选择拼团购买</li><li className="flex gap-2"><Check className="h-4 w-4 text-success" />Token 账单可追踪</li></ul><Button asChild className="mt-6 w-full"><Link to="/group-buy"><ShoppingCart className="mr-1 h-4 w-4" />进入购买大厅</Link></Button></CardContent></Card>)}</div>
    <div className="grid gap-4 md:grid-cols-2"><Card><CardContent className="flex gap-3 p-5"><Zap className="mt-0.5 h-5 w-5 text-primary" /><div><p className="font-medium">直购</p><p className="mt-1 text-sm text-foreground-muted">现金订单支付成功后，额度直接进入积分账户。</p></div></CardContent></Card><Card><CardContent className="flex gap-3 p-5"><ShoppingCart className="mt-0.5 h-5 w-5 text-primary" /><div><p className="font-medium">拼团</p><p className="mt-1 text-sm text-foreground-muted">加入已有队伍或发起新团，活动优惠由管理员配置。</p></div></CardContent></Card></div>
  </section>;
}

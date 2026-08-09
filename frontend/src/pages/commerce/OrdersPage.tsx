import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { orderStatusLabel, productLabel } from "@/lib/commerceLabels";
import { platformClient } from "@/platform/client";

interface OrderItem {
  orderId?: string;
  productName?: string;
  status?: string;
  displayStatus?: string;
  marketType?: unknown;
  amount?: number | string;
  payUrl?: string;
  paidAt?: string;
}

function isExternalPayUrl(value: unknown): value is string {
  return typeof value === "string" && /^https?:\/\//i.test(value.trim());
}

export function OrdersPage(): JSX.Element {
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load(): Promise<void> {
    setLoading(true);
    try {
      const response = await platformClient.get<{ data?: { items?: OrderItem[] } }>("/api/bff/orders");
      setOrders(response.data.data?.items ?? []);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "订单暂时不可用");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div><p className="text-sm font-medium text-primary">交易中心</p><h1 className="mt-2 text-3xl font-semibold">我的订单</h1><p className="mt-2 text-sm text-foreground-muted">现金支付、拼团状态、退款和积分到账都在同一条订单时间线上收敛。</p></div>
        <Button variant="outline" onClick={() => void load()} disabled={loading}>刷新</Button>
      </div>
      {error ? <div className="rounded-lg border border-danger/30 bg-danger/5 px-4 py-3 text-sm text-danger">{error}</div> : null}
      {loading ? <Card><CardContent className="p-8 text-center text-sm text-foreground-muted">正在读取订单…</CardContent></Card> : null}
      {!loading && orders.length === 0 ? <Card><CardContent className="p-10 text-center text-sm text-foreground-muted">还没有订单，先去参加一个积分拼团吧。</CardContent></Card> : null}
      <div className="space-y-3">
        {orders.map((order, index) => {
          const id = order.orderId ?? `order-${index}`;
          const status = orderStatusLabel(order.displayStatus ?? order.status, order.marketType);
          return <Card key={id}>
            <CardHeader className="flex flex-row items-start justify-between gap-4 space-y-0"><div><CardTitle className="text-base">{productLabel(order.productName)}</CardTitle><p className="mt-1 text-xs text-foreground-muted">订单号：{id}</p></div><span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">{status}</span></CardHeader>
            <CardContent className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>实付：¥{String(order.amount ?? "-")} {order.paidAt ? `· ${order.paidAt}` : ""}</span><div className="flex gap-2">{isExternalPayUrl(order.payUrl) ? <Button asChild size="sm"><a href={order.payUrl}>继续支付</a></Button> : null}<Button asChild variant="outline" size="sm"><Link to={`/checkout/${encodeURIComponent(id)}`}>查看收银台</Link></Button></div></CardContent>
          </Card>;
        })}
      </div>
    </div>
  );
}

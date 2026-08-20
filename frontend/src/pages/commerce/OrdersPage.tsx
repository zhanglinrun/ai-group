import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { PaymentDialog, type PaymentDialogState } from "@/components/commerce/PaymentDialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { orderStatusLabel, productLabel } from "@/lib/commerceLabels";
import { PAYMENT_WINDOW_MS } from "@/lib/countdown";
import { classifyPayPayload, isPayableStatus } from "@/lib/payPayload";
import { orderDateRows } from "@/lib/orderDates";
import { listOrders, type OrderItem } from "@/platform/orders";

function isGroupMarket(marketType: unknown): boolean {
  return marketType === 1 || marketType === "1" || marketType === "group_buy_market";
}

export function OrdersPage(): JSX.Element {
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [paymentDialog, setPaymentDialog] = useState<PaymentDialogState | null>(null);

  async function load(): Promise<void> {
    setLoading(true);
    try {
      setOrders(await listOrders());
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "订单暂时不可用");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  function resumePayment(order: OrderItem, orderId: string): void {
    const payload = classifyPayPayload(order.payUrl);
    if (payload.kind === "none") {
      return;
    }
    setPaymentDialog({
      orderId,
      title: productLabel(order.productName),
      amount: order.amount,
      qrCode: payload.kind === "qr" ? payload.qrCode : undefined,
      payUrl: payload.kind === "form" ? payload.formHtml : undefined,
      purchaseMode: isGroupMarket(order.marketType) ? "group" : "direct",
      paymentExpiresAt: Date.now() + PAYMENT_WINDOW_MS,
    });
  }

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
          const payable = isPayableStatus(order.displayStatus ?? order.status);
          const payload = classifyPayPayload(order.payUrl);
          return <Card key={id}>
            <CardHeader className="flex flex-row items-start justify-between gap-4 space-y-0"><div><CardTitle className="text-base">{productLabel(order.productName)}</CardTitle><p className="mt-1 text-xs text-foreground-muted">订单号：{id}</p>{orderDateRows(order).map((row) => <p key={row.label} className="mt-1 text-xs text-foreground-muted">{row.label}：{row.value}</p>)}</div><span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">{status}</span></CardHeader>
            <CardContent className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>实付：¥{String(order.amount ?? "-")}</span><div className="flex gap-2">{payable && payload.kind !== "none" ? <Button size="sm" onClick={() => resumePayment(order, id)}>继续支付</Button> : null}<Button asChild variant="outline" size="sm"><Link to={`/checkout/${encodeURIComponent(id)}`}>查看收银台</Link></Button></div></CardContent>
          </Card>;
        })}
      </div>
      <PaymentDialog payment={paymentDialog} onClose={() => setPaymentDialog(null)} onPaid={() => { setPaymentDialog(null); void load(); }} />
    </div>
  );
}

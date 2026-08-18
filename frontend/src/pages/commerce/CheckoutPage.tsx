import { useEffect, useState, Fragment } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DEFAULT_GROUP_VALID_MINUTES, parseTime, PAYMENT_WINDOW_MS } from "@/lib/countdown";
import { orderStatusLabel, productLabel } from "@/lib/commerceLabels";
import { classifyPayPayload, isPayableStatus } from "@/lib/payPayload";
import { orderDateRows } from "@/lib/orderDates";
import { platformClient } from "@/platform/client";
import { PaymentDialog, type PaymentDialogState } from "@/components/commerce/PaymentDialog";

interface PayOrder {
  orderId?: string;
  productCode?: string;
  totalAmount?: number | string;
  payAmount?: number | string;
  status?: string;
  payUrl?: string;
  orderTime?: string | number;
  payTime?: string | number;
  updateTime?: string | number;
  groupActivityId?: number;
  groupTeamId?: string;
}

export function CheckoutPage(): JSX.Element {
  const { orderId = "" } = useParams();
  const navigate = useNavigate();
  const [order, setOrder] = useState<PayOrder | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [paymentDialog, setPaymentDialog] = useState<PaymentDialogState | null>(null);

  async function load(): Promise<void> {
    if (!orderId) return;
    setLoading(true);
    try {
      const response = await platformClient.get<{ data?: PayOrder }>(`/api/pay/orders/${encodeURIComponent(orderId)}`);
      setOrder(response.data.data ?? null);
      setMessage(null);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "订单读取失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [orderId]);

  async function cancel(): Promise<void> {
    try {
      await platformClient.post(`/api/pay/orders/${encodeURIComponent(orderId)}/cancel`);
      await load();
      setMessage("订单状态已更新");
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "取消订单失败");
    }
  }

  const payable = isPayableStatus(order?.status);
  const payPayload = classifyPayPayload(order?.payUrl);
  const openStoredPayment = (): void => {
    if (!order || payPayload.kind === "none") return;
    const orderedAt = parseTime(order.orderTime) ?? Date.now();
    setPaymentDialog({
      orderId: order.orderId ?? orderId,
      title: productLabel(order.productCode),
      amount: order.payAmount ?? order.totalAmount,
      qrCode: payPayload.kind === "qr" ? payPayload.qrCode : undefined,
      payUrl: payPayload.kind === "form" ? payPayload.formHtml : undefined,
      purchaseMode: order.groupActivityId ? "group" : "direct",
      paymentExpiresAt: orderedAt + PAYMENT_WINDOW_MS,
      groupExpiresAt: order.groupActivityId ? orderedAt + DEFAULT_GROUP_VALID_MINUTES * 60 * 1000 : undefined,
    });
  };

  return (
    <div className="mx-auto w-full max-w-2xl space-y-6">
      <div>
        <p className="text-sm font-medium text-primary">安全收银台</p>
        <h1 className="mt-2 text-3xl font-semibold">确认支付</h1>
        <p className="mt-2 text-sm text-foreground-muted">现金支付、拼团状态和 Token 入账会在订单时间线上持续更新。</p>
      </div>
      {message ? <div className="rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning-foreground">{message}</div> : null}
      <Card>
        <CardHeader>
          <CardTitle>{loading ? "正在加载订单…" : productLabel(order?.productCode)}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {order ? (
            <>
              <dl className="grid grid-cols-2 gap-3 text-sm">
                <dt className="text-foreground-muted">订单号</dt>
                <dd className="text-right font-mono">{order.orderId ?? orderId}</dd>
                <dt className="text-foreground-muted">订单状态</dt>
                <dd className="text-right font-medium text-primary">{orderStatusLabel(order.status, order.groupActivityId ? 1 : 0)}</dd>
                <dt className="text-foreground-muted">订单金额</dt>
                <dd className="text-right">¥{String(order.totalAmount ?? "-")}</dd>
                <dt className="text-foreground-muted">应付金额</dt>
                <dd className="text-right text-xl font-semibold">¥{String(order.payAmount ?? "-")}</dd>
                <dt className="text-foreground-muted">拼团活动</dt>
                <dd className="text-right">{order.groupActivityId ? "已配置" : "直购"}</dd>
                <dt className="text-foreground-muted">队伍</dt>
                <dd className="text-right">{order.groupTeamId ?? "待分配"}</dd>
                {orderDateRows(order).map((row) => (
                  <Fragment key={row.label}>
                    <dt className="text-foreground-muted">{row.label}</dt>
                    <dd className="text-right">{row.value}</dd>
                  </Fragment>
                ))}
              </dl>
              <div className="flex flex-wrap gap-2 border-t border-border pt-4">
                {payable && payPayload.kind !== "none" ? <Button onClick={openStoredPayment}>继续支付</Button> : null}
                {payable && payPayload.kind === "none" ? <p className="w-full text-sm text-foreground-muted">当前未配置支付通道，订单已保留；请配置支付宝后继续。</p> : null}
                <Button variant="outline" onClick={() => void cancel()} disabled={!payable}>取消本订单</Button>
                <Button variant="ghost" onClick={() => navigate("/orders")}>返回订单</Button>
              </div>
            </>
          ) : <p className="text-sm text-foreground-muted">暂无订单数据。</p>}
        </CardContent>
      </Card>
      <p className="text-xs text-foreground-subtle">拼团支付后会进入队伍，达到目标人数后自动结算并发放 Token。</p>
      <Link className="text-sm text-primary hover:underline" to="/group-buy">返回拼团大厅 →</Link>
      <PaymentDialog payment={paymentDialog} onClose={() => setPaymentDialog(null)} onPaid={() => { setPaymentDialog(null); void load(); }} />
    </div>
  );
}

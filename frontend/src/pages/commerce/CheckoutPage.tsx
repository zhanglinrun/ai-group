import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { orderStatusLabel, productLabel } from "@/lib/commerceLabels";
import { platformClient } from "@/platform/client";
import { PaymentDialog, type PaymentDialogState } from "@/components/commerce/PaymentDialog";

interface PayOrder {
  orderId?: string;
  productCode?: string;
  totalAmount?: number | string;
  payAmount?: number | string;
  status?: string;
  payUrl?: string;
  groupActivityId?: number;
  groupTeamId?: string;
  demoCompletionEnabled?: boolean;
}

function isExternalPayUrl(value: unknown): value is string {
  return typeof value === "string" && /^https?:\/\//i.test(value.trim());
}

export function CheckoutPage(): JSX.Element {
  const { orderId = "" } = useParams();
  const navigate = useNavigate();
  const [order, setOrder] = useState<PayOrder | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [demoPaying, setDemoPaying] = useState(false);
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

  async function demoPay(): Promise<void> {
    if (!orderId || !order?.demoCompletionEnabled) return;
    setDemoPaying(true);
    setMessage(null);
    try {
      const endpoint = order.groupActivityId ? "/api/v1/alipay/demo_mark_paid" : "/api/v1/alipay/demo_complete";
      const response = await platformClient.post<{ data?: string; message?: string }>(endpoint, null, {
        params: { outTradeNo: orderId },
      });
      await load();
      setMessage(response.data.data === "SETTLED"
        ? "模拟支付成功，额度已进入入账流程。"
        : order.groupActivityId
          ? "模拟支付成功，订单已进入拼团，等待成团。"
          : "模拟支付成功，额度将自动入账。");
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "模拟支付失败，请稍后重试");
    } finally {
      setDemoPaying(false);
    }
  }

  // Pay service uses both CREATE and CREATED across legacy/current order rows.
  // Treat both as cancellable/payable so a failed lock or a freshly-created
  // sandbox order never gets stranded with every action disabled.
  const payable = ["PAY_WAIT", "WAIT_PAY", "CREATE", "CREATED"].includes(String(order?.status).toUpperCase());
  const externalPayUrl = isExternalPayUrl(order?.payUrl);
  const storedPayValue = typeof order?.payUrl === "string" ? order.payUrl.trim() : "";
  const storedPayForm = storedPayValue.toLowerCase().includes("<form");
  const storedQrCode = storedPayValue && !storedPayForm && !storedPayValue.startsWith("<") ? storedPayValue : undefined;
  const openStoredPayment = (): void => {
    if (!order) return;
    setPaymentDialog({
      orderId: order.orderId ?? orderId,
      title: order.productCode ?? "熊博士积分套餐",
      amount: order.payAmount ?? order.totalAmount,
      qrCode: storedQrCode,
      payUrl: storedPayForm ? storedPayValue : undefined,
      demoCompletionEnabled: order.demoCompletionEnabled,
      purchaseMode: order.groupActivityId ? "group" : "direct",
    });
  };
  return <div className="mx-auto w-full max-w-2xl space-y-6"><div><p className="text-sm font-medium text-primary">安全收银台</p><h1 className="mt-2 text-3xl font-semibold">确认支付</h1><p className="mt-2 text-sm text-foreground-muted">现金支付、拼团状态和 Token 入账会在订单时间线上持续更新。</p></div>{message ? <div className="rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning-foreground">{message}</div> : null}<Card><CardHeader><CardTitle>{loading ? "正在加载订单…" : productLabel(order?.productCode)}</CardTitle></CardHeader><CardContent className="space-y-4">{order ? <><dl className="grid grid-cols-2 gap-3 text-sm"><dt className="text-foreground-muted">订单号</dt><dd className="text-right font-mono">{order.orderId ?? orderId}</dd><dt className="text-foreground-muted">订单状态</dt><dd className="text-right font-medium text-primary">{orderStatusLabel(order.status, order.groupActivityId ? 1 : 0)}</dd><dt className="text-foreground-muted">订单金额</dt><dd className="text-right">¥{String(order.totalAmount ?? "-")}</dd><dt className="text-foreground-muted">应付金额</dt><dd className="text-right text-xl font-semibold">¥{String(order.payAmount ?? "-")}</dd><dt className="text-foreground-muted">拼团活动</dt><dd className="text-right">{order.groupActivityId ? "已配置" : "直购"}</dd><dt className="text-foreground-muted">队伍</dt><dd className="text-right">{order.groupTeamId ?? "待分配"}</dd></dl><div className="flex flex-wrap gap-2 border-t border-border pt-4">{externalPayUrl && payable ? <Button asChild><a href={order.payUrl}>打开支付宝沙箱</a></Button> : null}{payable && (storedQrCode || storedPayForm) ? <Button variant="outline" onClick={openStoredPayment}>重新打开支付窗口</Button> : null}{order.demoCompletionEnabled && payable ? <Button onClick={() => void demoPay()} disabled={demoPaying}>{demoPaying ? "处理中…" : order.groupActivityId ? "模拟支付并加入拼团" : "模拟支付"}</Button> : null}{payable && !externalPayUrl && !storedQrCode && !storedPayForm && !order.demoCompletionEnabled ? <p className="w-full text-sm text-foreground-muted">当前未配置支付通道，订单已保留；请配置支付宝后继续。</p> : null}<Button variant="outline" onClick={() => void cancel()} disabled={!payable || demoPaying}>取消未支付订单</Button><Button variant="ghost" onClick={() => navigate("/orders")}>返回订单</Button></div></> : <p className="text-sm text-foreground-muted">暂无订单数据。</p>}</CardContent></Card><p className="text-xs text-foreground-subtle">拼团支付后会进入队伍，达到目标人数后自动结算并发放 Token。</p><Link className="text-sm text-primary hover:underline" to="/group-buy">返回拼团大厅 →</Link><PaymentDialog payment={paymentDialog} onClose={() => setPaymentDialog(null)} onPaid={() => { setPaymentDialog(null); void load(); }} /></div>;
}

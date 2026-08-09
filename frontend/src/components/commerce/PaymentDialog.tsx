import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { CheckCircle2, Clock3, LoaderCircle, RefreshCw, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  completeDemoPayment,
  finalizeDemoGroup,
  markDemoGroupPaid,
  submitAlipayForm,
  syncPayOrder,
  type PurchaseMode,
} from "@/platform/pay";

export interface PaymentDialogState {
  orderId: string;
  title: string;
  amount?: number | string | null;
  qrCode?: string | null;
  payUrl?: string | null;
  demoCompletionEnabled?: boolean;
  purchaseMode: PurchaseMode;
}

interface PaymentDialogProps {
  payment: PaymentDialogState | null;
  onClose: () => void;
  onPaid: () => void;
}

const PAYMENT_WINDOW_SECONDS = 30 * 60;

function formatCountdown(seconds: number): string {
  const safeSeconds = Math.max(seconds, 0);
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const remainder = safeSeconds % 60;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

function isPaymentForm(value: unknown): value is string {
  return typeof value === "string" && value.trim().toLowerCase().includes("<form");
}

export function PaymentDialog({ payment, onClose, onPaid }: PaymentDialogProps): JSX.Element | null {
  const [qrImage, setQrImage] = useState("");
  const [remaining, setRemaining] = useState(PAYMENT_WINDOW_SECONDS);
  const [checking, setChecking] = useState(false);
  const [demoProcessing, setDemoProcessing] = useState(false);
  const [groupDemoPaid, setGroupDemoPaid] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const requestInFlight = useRef(false);
  const settled = useRef(false);

  const orderId = payment?.orderId;

  useEffect(() => {
    let alive = true;
    if (!payment?.qrCode) {
      setQrImage("");
      return () => {
        alive = false;
      };
    }
    QRCode.toDataURL(payment.qrCode, { width: 240, margin: 1 })
      .then((url) => {
        if (alive) setQrImage(url);
      })
      .catch(() => {
        if (alive) setQrImage("");
      });
    return () => {
      alive = false;
    };
  }, [payment?.qrCode]);

  useEffect(() => {
    settled.current = false;
    requestInFlight.current = false;
    setRemaining(PAYMENT_WINDOW_SECONDS);
    setGroupDemoPaid(false);
    setMessage(null);
  }, [orderId]);

  const checkStatus = useCallback(async () => {
    if (!orderId || settled.current || requestInFlight.current) return;
    requestInFlight.current = true;
    setChecking(true);
    try {
      const result = await syncPayOrder(orderId);
      if (result === "SETTLED" || result.startsWith("GROUP_")) {
        settled.current = true;
        setMessage("支付宝已确认支付，正在推进订单结算。");
        onPaid();
      } else {
        setMessage("暂未收到支付宝成功回调，请完成沙箱支付后再刷新状态。");
      }
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "查询支付状态失败");
    } finally {
      requestInFlight.current = false;
      setChecking(false);
    }
  }, [onPaid, orderId]);

  useEffect(() => {
    if (!payment || !orderId || (payment.purchaseMode === "group" && groupDemoPaid)) return;
    if (!payment.qrCode && !isPaymentForm(payment.payUrl)) return;
    const poll = window.setInterval(() => void checkStatus(), 3000);
    const tick = window.setInterval(() => {
      setRemaining((current) => {
        if (current <= 1) {
          window.clearInterval(poll);
          window.clearInterval(tick);
          return 0;
        }
        return current - 1;
      });
    }, 1000);
    return () => {
      window.clearInterval(poll);
      window.clearInterval(tick);
    };
  }, [checkStatus, groupDemoPaid, orderId, payment]);

  async function demoDirect(): Promise<void> {
    if (!orderId || requestInFlight.current || remaining <= 0) return;
    requestInFlight.current = true;
    setDemoProcessing(true);
    try {
      const result = await completeDemoPayment(orderId);
      if (result !== "SETTLED" && result !== "GROUP_FINALIZED") throw new Error("演示支付未完成");
      settled.current = true;
      setMessage("模拟支付成功，直购额度正在入账。");
      window.setTimeout(onPaid, 900);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "模拟支付失败");
    } finally {
      requestInFlight.current = false;
      setDemoProcessing(false);
    }
  }

  async function demoGroupMarkPaid(): Promise<void> {
    if (!orderId || requestInFlight.current || remaining <= 0) return;
    requestInFlight.current = true;
    setDemoProcessing(true);
    try {
      const result = await markDemoGroupPaid(orderId);
      if (result === "GROUP_FINALIZED") {
        settled.current = true;
        setMessage("拼团已达到目标，正在结算并发放额度。");
        window.setTimeout(onPaid, 900);
      } else if (result === "GROUP_WAITING") {
        setGroupDemoPaid(true);
        setMessage("当前成员已完成支付，订单已锁单，队伍继续开放参团。");
      } else {
        throw new Error("拼团演示支付未完成");
      }
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "模拟拼团支付失败");
    } finally {
      requestInFlight.current = false;
      setDemoProcessing(false);
    }
  }

  async function demoFinalizeGroup(): Promise<void> {
    if (!orderId || requestInFlight.current || !groupDemoPaid) return;
    requestInFlight.current = true;
    setDemoProcessing(true);
    try {
      const result = await finalizeDemoGroup(orderId);
      if (result !== "GROUP_FINALIZED") throw new Error("当前队伍尚未达到成团人数");
      settled.current = true;
      setMessage("拼团已封团，正在结算并发放额度。");
      window.setTimeout(onPaid, 900);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "封团失败，请确认已有 3 名成员完成支付");
    } finally {
      requestInFlight.current = false;
      setDemoProcessing(false);
    }
  }

  if (!payment) return null;

  const expired = remaining <= 0;
  const canOpenPagePay = isPaymentForm(payment.payUrl);
  const amount = payment.amount == null ? null : Number(payment.amount);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4 py-6 backdrop-blur-sm">
      <Card className="max-h-[calc(100vh-3rem)] w-full max-w-md overflow-y-auto shadow-raised">
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-primary">安全收银台</p>
            <CardTitle className="mt-1">{payment.purchaseMode === "group" ? "拼团支付" : "直接购买"}</CardTitle>
            <p className="mt-1 text-caption text-foreground-muted">{payment.title}</p>
          </div>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="关闭支付窗口"><X className="h-4 w-4" /></Button>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col items-center rounded-xl border border-border bg-white p-4">
            {qrImage ? <img src={qrImage} alt="支付宝沙箱支付二维码" className="h-56 w-56" /> : <div className="flex h-56 w-56 items-center justify-center px-5 text-center text-caption text-foreground-muted">{canOpenPagePay ? "扫码预下单暂不可用，可打开支付宝沙箱收银台完成支付" : payment.demoCompletionEnabled ? "当前使用本地演示支付，不会调用支付宝" : "支付二维码暂不可用，请稍后重试"}</div>}
            <div className="mt-3 flex items-center gap-2 text-caption text-foreground-muted"><Clock3 className="h-4 w-4" />支付剩余 {expired ? "已过期" : formatCountdown(remaining)}</div>
            {amount != null && Number.isFinite(amount) ? <p className="mt-1 text-2xl font-semibold text-primary">¥{amount.toFixed(2)}</p> : null}
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 rounded-xl bg-secondary/50 p-3 text-caption"><dt className="text-foreground-muted">订单号</dt><dd className="truncate text-right font-mono">{payment.orderId}</dd><dt className="text-foreground-muted">支付模式</dt><dd className="text-right">{payment.purchaseMode === "group" ? "锁单拼团" : "直购入账"}</dd><dt className="text-foreground-muted">当前状态</dt><dd className="text-right">{groupDemoPaid ? "已支付，等待成团" : "待支付"}</dd></dl>
          {message ? <div className="rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-caption text-warning-foreground">{message}</div> : null}
          <div className="flex flex-wrap gap-2">
            {canOpenPagePay ? <Button onClick={() => { try { submitAlipayForm(payment.payUrl ?? ""); } catch (cause) { setMessage(cause instanceof Error ? cause.message : "打开支付宝失败"); } }} disabled={expired}><RefreshCw className="h-4 w-4" />打开支付宝沙箱</Button> : null}
            <Button variant="outline" onClick={() => void checkStatus()} disabled={checking || expired || groupDemoPaid}><RefreshCw className={checking ? "h-4 w-4 animate-spin" : "h-4 w-4"} />刷新支付状态</Button>
          </div>
          {payment.demoCompletionEnabled ? <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-amber-900"><p className="text-caption font-medium">本地演示：仍走真实锁单与结算链路</p><p className="text-micro leading-5">演示只跳过支付宝扣款，不跳过订单、拼团库存、支付确认、成团和 Token 入账。点击“模拟支付并锁单”后，可补齐其余演示席位完成 3 人封团。</p>{payment.purchaseMode === "group" ? <><Button className="w-full bg-amber-700 hover:bg-amber-800" onClick={() => void demoGroupMarkPaid()} disabled={demoProcessing || expired || groupDemoPaid}>{demoProcessing && !groupDemoPaid ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}{groupDemoPaid ? "当前成员已支付并锁单" : "模拟支付并锁单"}</Button>{groupDemoPaid ? <Button variant="outline" className="w-full border-amber-700 text-amber-800" onClick={() => void demoFinalizeGroup()} disabled={demoProcessing}>补齐演示席位并封团</Button> : null}</> : <Button className="w-full bg-amber-700 hover:bg-amber-800" onClick={() => void demoDirect()} disabled={demoProcessing || expired}><CheckCircle2 className="h-4 w-4" />模拟支付并入账</Button>}</div> : null}
          <Button variant="ghost" className="w-full" onClick={onClose}>稍后支付，保留订单锁单</Button>
        </CardContent>
      </Card>
    </div>
  );
}

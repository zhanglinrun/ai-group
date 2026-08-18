import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { Clock3, RefreshCw, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCountdown, PAYMENT_WINDOW_MS, secondsUntil } from "@/lib/countdown";
import {
  syncPayOrder,
  type PurchaseMode,
} from "@/platform/pay";

export interface PaymentDialogState {
  orderId: string;
  title: string;
  amount?: number | string | null;
  qrCode?: string | null;
  payUrl?: string | null;
  purchaseMode: PurchaseMode;
  /** Unpaid close deadline (orderTime + 30m). */
  paymentExpiresAt?: string | number;
  /** Group validEndTime (lockTime + activity validTime). */
  groupExpiresAt?: string | number;
}

interface PaymentDialogProps {
  payment: PaymentDialogState | null;
  onClose: () => void;
  onPaid: () => void;
}

function isPaymentForm(value: unknown): value is string {
  return typeof value === "string" && value.trim().toLowerCase().includes("<form");
}

export function PaymentDialog({ payment, onClose, onPaid }: PaymentDialogProps): JSX.Element | null {
  const [qrImage, setQrImage] = useState("");
  const [payRemaining, setPayRemaining] = useState<number | null>(null);
  const [groupRemaining, setGroupRemaining] = useState<number | null>(null);
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const requestInFlight = useRef(false);
  const settled = useRef(false);
  const paymentDeadlineRef = useRef<number>(0);

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
    setMessage(null);
    paymentDeadlineRef.current = Date.now() + PAYMENT_WINDOW_MS;
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
        setMessage("暂未收到支付宝成功回调，请完成支付后再刷新状态。");
      }
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "查询支付状态失败");
    } finally {
      requestInFlight.current = false;
      setChecking(false);
    }
  }, [onPaid, orderId]);

  useEffect(() => {
    if (!payment || !orderId) return;
    const tick = (): void => {
      const now = Date.now();
      setPayRemaining(secondsUntil(payment.paymentExpiresAt ?? paymentDeadlineRef.current, now));
      setGroupRemaining(secondsUntil(payment.groupExpiresAt, now));
    };
    tick();
    if (!payment.qrCode && !isPaymentForm(payment.payUrl)) {
      const clock = window.setInterval(tick, 1000);
      return () => window.clearInterval(clock);
    }
    const poll = window.setInterval(() => void checkStatus(), 3000);
    const clock = window.setInterval(tick, 1000);
    return () => {
      window.clearInterval(poll);
      window.clearInterval(clock);
    };
  }, [checkStatus, orderId, payment]);

  if (!payment) return null;

  const expired = (payRemaining ?? 1) <= 0 || (groupRemaining != null && groupRemaining <= 0);
  const amount = payment.amount == null ? null : Number(payment.amount);
  const primarySeconds = payment.purchaseMode === "group" && groupRemaining != null ? groupRemaining : payRemaining;
  const primaryLabel = payment.purchaseMode === "group" && groupRemaining != null ? "成团剩余" : "支付剩余";

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
            {qrImage ? <img src={qrImage} alt="支付宝支付二维码" className="h-56 w-56" /> : <div className="flex h-56 w-56 items-center justify-center px-5 text-center text-caption text-foreground-muted">支付二维码暂不可用，请稍后重试</div>}
            <div className="mt-3 flex items-center gap-2 text-caption text-foreground-muted"><Clock3 className="h-4 w-4" />{primaryLabel} {expired ? "已过期" : formatCountdown(primarySeconds)}</div>
            {payment.purchaseMode === "group" && payRemaining != null ? <p className="mt-1 text-micro text-foreground-muted">请在 {formatCountdown(payRemaining)} 内完成支付，超时将关闭订单</p> : null}
            {amount != null && Number.isFinite(amount) ? <p className="mt-1 text-2xl font-semibold text-primary">¥{amount.toFixed(2)}</p> : null}
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 rounded-xl bg-secondary/50 p-3 text-caption"><dt className="text-foreground-muted">订单号</dt><dd className="truncate text-right font-mono">{payment.orderId}</dd><dt className="text-foreground-muted">支付模式</dt><dd className="text-right">{payment.purchaseMode === "group" ? "锁单拼团" : "直购入账"}</dd><dt className="text-foreground-muted">当前状态</dt><dd className="text-right">待支付</dd></dl>
          {message ? <div className="rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-caption text-warning-foreground">{message}</div> : null}
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => void checkStatus()} disabled={checking || expired}><RefreshCw className={checking ? "h-4 w-4 animate-spin" : "h-4 w-4"} />刷新支付状态</Button>
          </div>
          <Button variant="ghost" className="w-full" onClick={onClose}>稍后支付，保留订单锁单</Button>
        </CardContent>
      </Card>
    </div>
  );
}

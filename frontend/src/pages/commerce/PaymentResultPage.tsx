import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { platformClient } from "@/platform/client";

export function PaymentResultPage(): JSX.Element {
  const [params] = useSearchParams();
  const orderId = params.get("orderId") ?? params.get("out_trade_no");
  const [status, setStatus] = useState("正在核对支付回调…");
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (!orderId) { setStatus("缺少订单号，请从订单中心进入收银台。"); return; }
    let active = true;
    let timer: number | undefined;
    const poll = async (): Promise<void> => {
      try {
        const response = await platformClient.get<{ data?: Record<string, unknown> }>(`/api/pay/orders/${encodeURIComponent(orderId)}`);
        if (!active) return;
        const data = response.data.data ?? null;
        setDetail(data);
        const orderStatus = String(data?.status ?? "UNKNOWN").toUpperCase();
        setStatus(orderStatus === "PAID" || orderStatus === "DEAL_DONE" || orderStatus === "MARKET" ? "支付已确认，正在等待成团和积分入账。" : `当前支付状态：${orderStatus}`);
        if (!["PAID", "DEAL_DONE", "MARKET", "REFUNDED", "CLOSED"].includes(orderStatus)) timer = window.setTimeout(() => void poll(), 2500);
      } catch { if (active) setStatus("支付结果暂未收敛，请稍后刷新订单。"); }
    };
    void poll();
    return () => { active = false; if (timer) window.clearTimeout(timer); };
  }, [orderId]);

  return <div className="mx-auto w-full max-w-2xl space-y-6"><p className="text-sm font-medium text-primary">支付结果</p><h1 className="text-3xl font-semibold">订单状态追踪</h1><Card><CardHeader><CardTitle>{status}</CardTitle></CardHeader><CardContent className="space-y-4 text-sm"><p className="text-foreground-muted">订单号：{orderId ?? "-"}</p>{detail ? <dl className="grid grid-cols-2 gap-3"><dt className="text-foreground-muted">支付状态</dt><dd className="text-right">{String(detail.status ?? "-")}</dd><dt className="text-foreground-muted">支付回调</dt><dd className="text-right">{detail.payTime ? "已收到" : "等待回调"}</dd><dt className="text-foreground-muted">积分到账</dt><dd className="text-right">{String(detail.benefitStatus ?? "由 Member 异步处理")}</dd></dl> : null}<div className="flex gap-2"><Button asChild><Link to="/orders">查看订单</Link></Button><Button asChild variant="outline"><Link to="/account">查看积分账户</Link></Button></div></CardContent></Card></div>;
}

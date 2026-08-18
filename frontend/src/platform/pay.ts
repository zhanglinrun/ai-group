import { platformClient } from "@/platform/client";

export type PurchaseMode = "direct" | "group";

export interface CreatePayOrderRequest {
  requestId: string;
  productId: string;
  productCode: string;
  activityId?: number;
  marketType: 0 | 1;
  teamId?: string;
}

export interface CreatePayQrOrder {
  orderId: string;
  qrCode?: string | null;
  payUrl?: string | null;
  amount?: number | string | null;
}

interface PaymentEnvelope<T> {
  code?: string | number;
  info?: string;
  message?: string;
  data?: T;
}

function unwrap<T>(payload: PaymentEnvelope<T>): T {
  if (String(payload.code ?? "") !== "0000") {
    throw new Error(payload.info || payload.message || "支付服务暂时不可用");
  }
  if (payload.data == null) {
    throw new Error("支付服务未返回订单信息");
  }
  return payload.data;
}

export async function createPayQrOrder(request: CreatePayOrderRequest): Promise<CreatePayQrOrder> {
  const response = await platformClient.post<PaymentEnvelope<CreatePayQrOrder>>(
    "/api/v1/alipay/create_pay_qrcode",
    request,
  );
  return unwrap(response.data);
}

export async function syncPayOrder(orderId: string): Promise<string> {
  const response = await platformClient.post<PaymentEnvelope<string>>(
    "/api/v1/alipay/sync_settle",
    null,
    { params: { outTradeNo: orderId } },
  );
  return unwrap(response.data);
}

/** 打开服务端生成的支付宝 page-pay HTML 表单，作为扫码预下单失败时的沙箱回退。 */
export function submitAlipayForm(html: string): void {
  const trimmed = html.trim();
  if (!trimmed || !trimmed.toLowerCase().includes("<form")) {
    throw new Error("支付宝支付表单无效");
  }

  const payWindow = window.open("", "_blank");
  if (payWindow && !payWindow.closed) {
    payWindow.document.open();
    payWindow.document.write(
      `<!doctype html><html><head><meta charset="UTF-8"><title>支付宝沙箱支付</title></head><body>${trimmed}<script>window.opener=null;var form=document.forms[0];if(form){form.submit();}</script></body></html>`,
    );
    payWindow.document.close();
    return;
  }

  const container = document.createElement("div");
  container.hidden = true;
  container.innerHTML = trimmed;
  const form = container.querySelector("form");
  if (!form) throw new Error("支付宝支付表单无效");
  form.target = "_blank";
  document.body.appendChild(container);
  form.submit();
  window.setTimeout(() => container.remove(), 1000);
}


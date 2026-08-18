export type ClassifiedPayPayload =
  | { kind: "qr"; qrCode: string }
  | { kind: "form"; formHtml: string }
  | { kind: "none" };

/**
 * Alipay face-to-face precreate returns an https://qr.alipay.com/... string.
 * That URL must be rendered as a QR image, never opened as a browser link —
 * a desktop navigation lands on the Alipay homepage instead of a cashier.
 * Page-pay fallback is an HTML form that we auto-submit in a new window.
 */
export function classifyPayPayload(value: unknown): ClassifiedPayPayload {
  if (typeof value !== "string") {
    return { kind: "none" };
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return { kind: "none" };
  }
  if (trimmed.toLowerCase().includes("<form")) {
    return { kind: "form", formHtml: trimmed };
  }
  if (trimmed.startsWith("<")) {
    return { kind: "none" };
  }
  return { kind: "qr", qrCode: trimmed };
}

export function isPayableStatus(status: unknown): boolean {
  return ["PAY_WAIT", "WAIT_PAY", "CREATE", "CREATED"].includes(String(status ?? "").toUpperCase());
}

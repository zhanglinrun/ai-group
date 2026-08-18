import { parseTime, PAYMENT_WINDOW_MS } from "@/lib/countdown";

export function formatOrderDateTime(value: unknown): string {
  const ms = parseTime(value as string | number | Date | null | undefined);
  if (ms == null) return "";
  return new Date(ms).toLocaleString("zh-CN", {
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function isGroupOrder(marketType: unknown, groupActivityId: unknown): boolean {
  if (groupActivityId != null && String(groupActivityId) !== "" && Number(groupActivityId) !== 0) {
    return true;
  }
  return marketType === 1 || marketType === "1" || marketType === "group_buy_market";
}

export interface OrderDateRow {
  label: string;
  value: string;
}

export function orderDateRows(order: {
  status?: unknown;
  displayStatus?: unknown;
  marketType?: unknown;
  groupActivityId?: unknown;
  orderTime?: unknown;
  payTime?: unknown;
  paidAt?: unknown;
  updateTime?: unknown;
}): OrderDateRow[] {
  const status = String(order.displayStatus ?? order.status ?? "").toUpperCase();
  const group = isGroupOrder(order.marketType, order.groupActivityId);
  const rows: OrderDateRow[] = [];

  const created = formatOrderDateTime(order.orderTime);
  if (created) {
    rows.push({ label: "创建时间", value: created });
  }

  const unpaid = ["PAY_WAIT", "WAIT_PAY", "CREATE", "CREATED"].includes(status);
  if (unpaid) {
    const orderedAt = parseTime(order.orderTime as string | number | Date | null | undefined);
    if (orderedAt != null) {
      const deadline = formatOrderDateTime(orderedAt + PAYMENT_WINDOW_MS);
      if (deadline) {
        rows.push({ label: "支付截止", value: deadline });
      }
    }
  }

  const paidAt = formatOrderDateTime(order.payTime ?? order.paidAt);
  const updated = formatOrderDateTime(order.updateTime);
  const closed = ["CLOSE", "CLOSED"].includes(status);
  // Unpaid timeout/reject close used to write pay_time = now(). Hide that fake payment.
  const paidStampLooksLikeClose = closed && Boolean(paidAt) && paidAt === updated;
  if (paidAt && !unpaid && !paidStampLooksLikeClose) {
    rows.push({ label: "支付时间", value: paidAt });
  }

  if (updated && closed) {
    rows.push({ label: "关闭时间", value: updated });
  }
  if (updated && ["DEAL_DONE", "MARKET", "GROUP_FORMED", "BENEFIT_GRANTED"].includes(status)) {
    rows.push({ label: group ? "成团时间" : "完成时间", value: updated });
  }
  if (updated && ["WAIT_REFUND", "REFUNDED"].includes(status)) {
    rows.push({ label: status === "REFUNDED" ? "退款时间" : "退款处理时间", value: updated });
  }

  return rows;
}

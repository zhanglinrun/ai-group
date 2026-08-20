import { platformClient } from "@/platform/client";

export interface OrderItem {
  orderId?: string;
  productName?: string;
  status?: string;
  displayStatus?: string;
  marketType?: unknown;
  amount?: number | string;
  payUrl?: string;
  orderTime?: string;
  payTime?: string;
  paidAt?: string;
  updateTime?: string;
  groupStatus?: string | null;
}

function isGroupBuyMarket(marketType: unknown): boolean {
  return marketType === 1 || marketType === "1" || marketType === "group_buy_market";
}

function normalizePayStatus(payStatus: unknown): string {
  return typeof payStatus === "string" ? payStatus.trim().toUpperCase() : "";
}

function mapDisplayStatus(payStatus: string, marketType: unknown): string {
  switch (payStatus) {
    case "PAY_WAIT":
    case "CREATE":
      return "PAY_WAIT";
    case "PAY_SUCCESS":
      return isGroupBuyMarket(marketType) ? "PAID_WAIT_GROUP" : "PAID";
    case "DEAL_DONE":
      return isGroupBuyMarket(marketType) ? "GROUP_FORMED" : "PAID";
    case "MARKET":
      return "GROUP_FORMED";
    case "WAIT_REFUND":
      return "WAIT_REFUND";
    case "CLOSE":
      return "CLOSED";
    default:
      return payStatus;
  }
}

function mapGroupStatus(payStatus: string): string {
  switch (payStatus) {
    case "DEAL_DONE":
    case "MARKET":
      return "formed";
    case "PAY_SUCCESS":
      return "waiting";
    default:
      return payStatus.toLowerCase();
  }
}

function toNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function resolveDisplayAmount(
  totalAmount: unknown,
  deductionAmount: unknown,
  payAmount: unknown,
): number | string | undefined {
  const total = toNumber(totalAmount);
  if (total == null) {
    return payAmount as number | string | undefined;
  }
  const deduction = toNumber(deductionAmount) ?? 0;
  const display = total - deduction;
  if (display < 0) {
    return payAmount as number | string | undefined;
  }
  return display;
}

function mapOrder(raw: Record<string, unknown>): OrderItem {
  const status = normalizePayStatus(raw.status);
  const marketType = raw.marketType;
  return {
    orderId: typeof raw.orderId === "string" ? raw.orderId : undefined,
    productName: typeof raw.productName === "string" ? raw.productName : undefined,
    status,
    displayStatus: mapDisplayStatus(status, marketType),
    marketType,
    amount: resolveDisplayAmount(raw.totalAmount, raw.marketDeductionAmount, raw.payAmount),
    payUrl: typeof raw.payUrl === "string" ? raw.payUrl : undefined,
    orderTime: typeof raw.orderTime === "string" ? raw.orderTime : undefined,
    payTime: typeof raw.payTime === "string" ? raw.payTime : undefined,
    paidAt: typeof raw.payTime === "string" ? raw.payTime : undefined,
    updateTime: typeof raw.updateTime === "string" ? raw.updateTime : undefined,
    groupStatus: isGroupBuyMarket(marketType) ? mapGroupStatus(status) : null,
  };
}

export async function listOrders(): Promise<OrderItem[]> {
  const { data } = await platformClient.post<{
    code?: string | number;
    data?: { orderList?: Record<string, unknown>[] };
  }>("/api/pay/orders/page", { lastId: null, pageSize: 20 });
  const orderList = data.data?.orderList;
  if (!Array.isArray(orderList)) {
    return [];
  }
  return orderList.flatMap((item) => (item && typeof item === "object" ? [mapOrder(item)] : []));
}

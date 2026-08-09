const PRODUCT_LABELS: Record<string, string> = {
  QUOTA_LIGHT: "轻享额度包",
  QUOTA_STANDARD: "标准额度包",
  QUOTA_LARGE: "大额额度包",
};

export function productLabel(value: unknown): string {
  const text = typeof value === "string" ? value.trim() : "";
  return PRODUCT_LABELS[text] ?? (text || "熊博士积分套餐");
}

export function orderStatusLabel(value: unknown, marketType?: unknown): string {
  const status = typeof value === "string" ? value.trim().toUpperCase() : "";
  const group = marketType === 1 || marketType === "1" || marketType === "group_buy_market";
  switch (status) {
    case "PAY_WAIT":
    case "WAIT_PAY":
    case "CREATE":
    case "CREATED":
      return "待支付";
    case "PAY_SUCCESS":
    case "PAID_WAIT_GROUP":
      return group ? "已支付，等待成团" : "已支付";
    case "DEAL_DONE":
    case "MARKET":
    case "GROUP_FORMED":
      return group ? "已成团，额度发放中" : "已完成";
    case "BENEFIT_GRANTED":
      return "已入账";
    case "WAIT_REFUND":
      return "退款处理中";
    case "CLOSE":
    case "CLOSED":
      return "已关闭";
    case "REFUNDED":
      return "已退款";
    default:
      return status || "处理中";
  }
}

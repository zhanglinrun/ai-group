import api from "./index";

export interface CreatePayOrderRequest {
  userId: string;
  productId: string;
  productCode: string;
  teamId?: string;
  activityId?: number;
  marketType?: number;
}

export const payApi = {
  createOrder: (payload: CreatePayOrderRequest) =>
    api.post<string>("/api/v1/alipay/create_pay_order", payload) as unknown as Promise<string>,

  /**
   * 支付回跳后的同步结算：后端主动向支付宝查单并触发结算（幂等）。
   * 返回 "SETTLED" 表示已结算；"UNPAID:*" 表示交易未成功。
   */
  syncSettle: (outTradeNo: string) =>
    api.post<string>(
      `/api/v1/alipay/sync_settle?outTradeNo=${encodeURIComponent(outTradeNo)}`
    ) as unknown as Promise<string>,
};

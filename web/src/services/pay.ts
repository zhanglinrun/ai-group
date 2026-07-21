import api from './index';

export interface CreatePayOrderRequest {
  /** 单次购买尝试内稳定；重复传同一值只能回放同一订单。 */
  requestId: string;
  userId: string;
  productId: string;
  productCode: string;
  teamId?: string;
  activityId?: number;
  marketType?: number;
}

/** 当面付/扫码支付下单应答：qrCode 供前端渲染二维码，orderId 用于轮询 sync_settle */
export interface CreateQrOrderResponse {
  orderId: string;
  qrCode?: string | null;
  /** 服务端订单快照计算出的权威展示应付金额。 */
  amount?: number;
  /** 仅 dev + 显式演示开关开启时返回 true。 */
  demoCompletionEnabled?: boolean;
}

export const payApi = {
  createOrder: (payload: CreatePayOrderRequest) =>
    api.post<string>('/api/v1/alipay/create_pay_order', payload) as unknown as Promise<string>,

  /** 扫码支付下单：返回订单号 + 支付宝 qr_code（当面付）。 */
  createQrOrder: (payload: CreatePayOrderRequest) =>
    api.post<CreateQrOrderResponse>(
      '/api/v1/alipay/create_pay_qrcode',
      payload,
    ) as unknown as Promise<CreateQrOrderResponse>,

  /**
   * 支付回跳后的同步结算：后端主动向支付宝查单并触发结算（幂等）。
   * 返回 "SETTLED" 表示已结算；"UNPAID:*" 表示交易未成功。
   */
  syncSettle: (outTradeNo: string) =>
    api.post<string>(
      `/api/v1/alipay/sync_settle?outTradeNo=${encodeURIComponent(outTradeNo)}`,
    ) as unknown as Promise<string>,

  /** Dev-only：由登录用户触发本地演示支付；生产环境没有该路由。 */
  completeDemoPayment: (outTradeNo: string) =>
    api.post<string>(
      `/api/v1/alipay/demo_complete?outTradeNo=${encodeURIComponent(outTradeNo)}`,
    ) as unknown as Promise<string>,

  /** Dev-only：仅登记拼团成员已支付，保留队伍供第二个用户参团。 */
  markDemoGroupPaid: (outTradeNo: string) =>
    api.post<string>(
      `/api/v1/alipay/demo_mark_paid?outTradeNo=${encodeURIComponent(outTradeNo)}`,
    ) as unknown as Promise<string>,

  /** Dev-only：在至少演示完参团和付款后，显式封团并触发真实结算/MQ 权益链路。 */
  finalizeDemoGroup: (outTradeNo: string) =>
    api.post<string>(
      `/api/v1/alipay/demo_finalize_group?outTradeNo=${encodeURIComponent(outTradeNo)}`,
    ) as unknown as Promise<string>,
};

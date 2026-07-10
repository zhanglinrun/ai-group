import api from './index';

export interface SkuItem {
  code: string;
  name: string;
  price: number;
  periodQuota?: number;
  topupQuota?: number;
  memberDays?: number;
  tier?: string;
  skuType?: string;
  /** 该 SKU 对应的拼团商品ID（无则不支持拼团） */
  groupGoodsId?: string;
  /** 该 SKU 对应的拼团活动ID（无则不支持拼团） */
  groupActivityId?: number;
  /** 该 SKU 的拼团价（BFF 按各自活动计算） */
  groupPayPrice?: number;
  /** 该 SKU 拼团立减金额 */
  groupDeductionPrice?: number;
  /** 该 SKU 拼团原价（group 商品价） */
  groupOriginalPrice?: number;
}

export interface GroupBuyGoods {
  goodsId?: string;
  originalPrice?: number;
  deductionPrice?: number;
  payPrice?: number;
}

export interface GroupBuyTeam {
  userId?: string;
  teamId?: string;
  activityId?: number;
  targetCount?: number;
  completeCount?: number;
  lockCount?: number;
  /** 拼团开始/结束时间（ISO 字符串），前端据 validEndTime 实时倒计时 */
  validStartTime?: string;
  validEndTime?: string;
  /** 后端查询瞬间生成的静态倒计时串（仅作无 validEndTime 时的回退展示） */
  validTimeCountdown?: string;
}

export interface GroupBuyInfo {
  activityId?: number;
  goods?: GroupBuyGoods;
  teamList?: GroupBuyTeam[];
  teamStatistic?: {
    allTeamCount?: number;
    allTeamCompleteCount?: number;
    allTeamUserCount?: number;
  };
}

export interface PricingResponse {
  skus: SkuItem[];
  groupBuy?: GroupBuyInfo;
  meta?: {
    degraded?: boolean;
    errors?: Array<{ service?: string; code?: string; message?: string }>;
  };
}

export interface GroupBuyResponse {
  activityId?: number;
  groupBuy?: GroupBuyInfo;
  skus?: SkuItem[];
  meta?: {
    degraded?: boolean;
    errors?: Array<{ service?: string; code?: string; message?: string }>;
  };
}

export interface PendingGroupOrder {
  orderId: string;
  status?: string;
  productName?: string;
  paidAt?: string;
}

export interface AccountSummary {
  userId?: number;
  tier?: string;
  startAt?: string;
  expireAt?: string;
  periodQuotaBalance?: number;
  topupQuotaBalance?: number;
  frozenBalance?: number;
  availableQuota?: number;
  pendingGroupOrders?: PendingGroupOrder[];
  meta?: {
    degraded?: boolean;
    errors?: Array<{ service?: string; code?: string; message?: string }>;
  };
}

export interface OrderItem {
  orderId: string;
  status?: string;
  displayStatus?: string;
  productName?: string;
  amount?: number;
  paidAt?: string;
  groupStatus?: string;
  marketType?: number;
  /** 待支付(PAY_WAIT)订单的收银台表单 HTML，用于「去支付」恢复支付 */
  payUrl?: string;
}

export interface OrdersResponse {
  items: OrderItem[];
  meta?: {
    degraded?: boolean;
    errors?: Array<{ service?: string; code?: string; message?: string }>;
  };
}

export const bffApi = {
  getPricing: () =>
    api.get<PricingResponse>('/api/bff/pricing') as unknown as Promise<PricingResponse>,

  getGroupBuy: (activityId: number) =>
    api.get<GroupBuyResponse>(
      `/api/bff/group-buy/${activityId}`,
    ) as unknown as Promise<GroupBuyResponse>,

  getAccountSummary: () =>
    api.get<AccountSummary>('/api/bff/account/summary') as unknown as Promise<AccountSummary>,

  getOrders: () => api.get<OrdersResponse>('/api/bff/orders') as unknown as Promise<OrdersResponse>,
};

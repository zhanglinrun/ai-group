import api from './index';

/** 阶梯拼团档位：达到 targetCount 人时，在基础额度之上累计加赠 bonusQuota 额度 */
export interface GroupBuyTier {
  tierNo?: number;
  tierName?: string;
  targetCount?: number;
  bonusQuota?: number;
}

export interface SkuItem {
  code: string;
  name: string;
  price: number;
  /** 购买后发放的永久额度，单位：额度点 */
  baseQuota?: number;
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
  /** 该 SKU 活动类型（0经典折扣、1阶梯额度）。BFF 从 group 透传 */
  groupActivityType?: number;
  /** 该 SKU 的阶梯档位（人数→累计加赠额度），仅阶梯额度拼团有值 */
  groupTiers?: GroupBuyTier[];
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
  /** 阶梯额度拼团：当前团已达档位序号（0 表示尚未达到任何档位） */
  reachedTierNo?: number;
  /** 阶梯额度拼团：下一档位所需人数（null 表示已达最高档） */
  nextTierTargetCount?: number;
  /** 阶梯额度拼团：最高档位人数（用于展示 X/最高档 进度） */
  maxTierTargetCount?: number;
  /** 创建团队时保存的阶梯快照；老团展示优先使用，避免运营改档后发生变化 */
  tiers?: GroupBuyTier[];
}

export interface GroupBuyInfo {
  activityId?: number;
  /** 活动类型（0经典折扣、1阶梯额度） */
  activityType?: number;
  goods?: GroupBuyGoods;
  /** 阶梯档位（人数→累计加赠额度），仅阶梯额度拼团有值 */
  tiers?: GroupBuyTier[];
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

export interface QuotaLedgerEntry {
  id?: number;
  type?: string;
  amount?: number;
  freezeId?: string;
  abilityCode?: string;
  remark?: string;
  createdAt?: string;
}

export interface AccountSummary {
  userId?: number;
  /** 以下余额均为微额度，1 额度点 = 1,000,000 微额度 */
  freeQuotaBalance?: number;
  paidQuotaBalance?: number;
  frozenBalance?: number;
  availableQuota?: number;
  quotaLedger?: QuotaLedgerEntry[];
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

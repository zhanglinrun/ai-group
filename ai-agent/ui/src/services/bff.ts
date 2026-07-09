import api from "./index";

export interface SkuItem {
  code: string;
  name: string;
  price: number;
  periodQuota?: number;
  topupQuota?: number;
  memberDays?: number;
  tier?: string;
  skuType?: string;
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
    api.get<PricingResponse>("/api/bff/pricing") as unknown as Promise<PricingResponse>,

  getGroupBuy: (activityId: number) =>
    api.get<GroupBuyResponse>(`/api/bff/group-buy/${activityId}`) as unknown as Promise<GroupBuyResponse>,

  getAccountSummary: () =>
    api.get<AccountSummary>("/api/bff/account/summary") as unknown as Promise<AccountSummary>,

  getOrders: () =>
    api.get<OrdersResponse>("/api/bff/orders") as unknown as Promise<OrdersResponse>,
};

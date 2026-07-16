import api from './index';

/** 永久额度包（member_db.product_sku） */
export interface AdminSku {
  id: number;
  code: string;
  name: string;
  price: number;
  /** 发放额度，单位：额度点 */
  baseQuota?: number;
  status?: number;
  groupGoodsId?: string | null;
  groupActivityId?: number | null;
}

/** 拼团活动（group 运营端联查行：活动 + 折扣 + 商品） */
export interface AdminGroupActivity {
  activityId: number;
  activityName?: string;
  discountId?: string;
  discountName?: string;
  marketPlan?: 'ZJ' | 'MJ' | 'ZK' | 'N';
  marketExpr?: string;
  takeLimitCount?: number;
  target?: number;
  validTime?: number;
  status?: number;
  goodsId?: string;
  goodsName?: string;
  originalPrice?: number;
  groupPayPrice?: number;
  activityType?: number;
  tiers?: AdminGroupTier[];
}

export interface AdminGroupTier {
  id?: number;
  activityId?: number;
  tierNo: number;
  tierName: string;
  targetCount: number;
  bonusQuota: number;
  status: number;
}

export interface AdminGroupActivityUpdate {
  activityName?: string;
  takeLimitCount?: number;
  target?: number;
  validTime?: number;
  status?: number;
  marketExpr?: string;
  marketPlan?: 'ZJ' | 'MJ' | 'ZK' | 'N';
  goodsName?: string;
  originalPrice?: number;
  activityType?: number;
}

export interface AdminGroupActivityCreate extends AdminGroupActivityUpdate {
  activityId: number;
  activityName: string;
  goodsId: string;
  goodsName: string;
  discountId: string;
  originalPrice: number;
  marketExpr: string;
  marketPlan: 'ZJ' | 'MJ' | 'ZK' | 'N';
}

/** 模型 API 配置（agent_db.ai_client_api，api_key 读时已脱敏） */
export interface AdminClientApi {
  id: number;
  apiId: string;
  baseUrl: string;
  apiKey: string;
  completionsPath?: string;
  embeddingsPath?: string;
  status?: number;
}

export interface AdminClientModel {
  id: number;
  modelId: string;
  modelName: string;
  modelType?: string;
  modelUsage?: string;
  apiId?: string;
  /** 每百万输入/输出 Token 消耗的额度点 */
  inputCreditsPerMillion?: number;
  outputCreditsPerMillion?: number;
  status?: number;
}

/**
 * 运营端接口客户端。响应包（member 的 {code:200} 与 group/agent 的 {code:"0000"}）
 * 均由全局 axios 拦截器解包，这里直接拿业务数据。
 */
export const adminApi = {
  // ---- 永久额度包 / 价格（member-service） ----
  listSkus: () => api.get<AdminSku[]>('/api/member/admin/skus') as unknown as Promise<AdminSku[]>,

  updateSku: (code: string, body: Partial<AdminSku>) =>
    api.put<AdminSku>(`/api/member/admin/skus/${code}`, body) as unknown as Promise<AdminSku>,

  createSku: (body: Partial<AdminSku>) =>
    api.post<AdminSku>('/api/member/admin/skus', body) as unknown as Promise<AdminSku>,

  deleteSku: (code: string) =>
    api.delete<void>(
      `/api/member/admin/skus/${encodeURIComponent(code)}`,
    ) as unknown as Promise<void>,

  // ---- 拼团活动 / 折扣 / 商品（group 服务） ----
  listGroupActivities: () =>
    api.get<AdminGroupActivity[]>('/api/group/admin/activities') as unknown as Promise<
      AdminGroupActivity[]
    >,

  updateGroupActivity: (activityId: number, body: AdminGroupActivityUpdate) =>
    api.put<boolean>(
      `/api/group/admin/activities/${activityId}`,
      body,
    ) as unknown as Promise<boolean>,

  createGroupActivity: (body: AdminGroupActivityCreate) =>
    api.post<boolean>('/api/group/admin/activities', body) as unknown as Promise<boolean>,

  replaceGroupActivityTiers: (activityId: number, tiers: AdminGroupTier[]) =>
    api.put<boolean>(
      `/api/group/admin/activities/${activityId}/tiers`,
      tiers,
    ) as unknown as Promise<boolean>,

  // ---- 模型 Key（ai-agent 管理接口，经网关 /api/v1/admin/**） ----
  listClientApis: () =>
    api.get<AdminClientApi[]>('/api/v1/admin/ai-client-api/query-all') as unknown as Promise<
      AdminClientApi[]
    >,

  updateClientApi: (body: Partial<AdminClientApi>) =>
    api.put<boolean>(
      '/api/v1/admin/ai-client-api/update-by-api-id',
      body,
    ) as unknown as Promise<boolean>,

  createClientApi: (body: Partial<AdminClientApi>) =>
    api.post<boolean>('/api/v1/admin/ai-client-api/create', body) as unknown as Promise<boolean>,

  deleteClientApi: (apiId: string) =>
    api.delete<boolean>(
      `/api/v1/admin/ai-client-api/delete-by-api-id/${encodeURIComponent(apiId)}`,
    ) as unknown as Promise<boolean>,

  listClientModels: () =>
    api.get<AdminClientModel[]>('/api/v1/admin/ai-client-model/query-all') as unknown as Promise<
      AdminClientModel[]
    >,

  createClientModel: (body: Partial<AdminClientModel>) =>
    api.post<boolean>('/api/v1/admin/ai-client-model/create', body) as unknown as Promise<boolean>,

  updateClientModel: (body: Partial<AdminClientModel>) =>
    api.put<boolean>(
      '/api/v1/admin/ai-client-model/update-by-model-id',
      body,
    ) as unknown as Promise<boolean>,

  deleteClientModel: (modelId: string) =>
    api.delete<boolean>(
      `/api/v1/admin/ai-client-model/delete-by-model-id/${encodeURIComponent(modelId)}`,
    ) as unknown as Promise<boolean>,
};

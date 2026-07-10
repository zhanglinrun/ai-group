import api from './index';

/** 会员 SKU（member_db.product_sku 全字段行） */
export interface AdminSku {
  id: number;
  code: string;
  name: string;
  price: number;
  periodQuota?: number;
  topupQuota?: number;
  memberDays?: number;
  tier?: string;
  skuType?: string;
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
  marketPlan?: string;
  marketExpr?: string;
  takeLimitCount?: number;
  target?: number;
  validTime?: number;
  status?: number;
  goodsId?: string;
  goodsName?: string;
  originalPrice?: number;
  groupPayPrice?: number;
}

export interface AdminGroupActivityUpdate {
  activityName?: string;
  takeLimitCount?: number;
  target?: number;
  validTime?: number;
  status?: number;
  marketExpr?: string;
  goodsName?: string;
  originalPrice?: number;
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
  apiId?: string;
  status?: number;
}

/**
 * 运营端接口客户端。响应包（member 的 {code:200} 与 group/agent 的 {code:"0000"}）
 * 均由全局 axios 拦截器解包，这里直接拿业务数据。
 */
export const adminApi = {
  // ---- 会员套餐 / 价格（member-service） ----
  listSkus: () => api.get<AdminSku[]>('/api/member/admin/skus') as unknown as Promise<AdminSku[]>,

  updateSku: (code: string, body: Partial<AdminSku>) =>
    api.put<AdminSku>(`/api/member/admin/skus/${code}`, body) as unknown as Promise<AdminSku>,

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

  listClientModels: () =>
    api.get<AdminClientModel[]>('/api/v1/admin/ai-client-model/query-all') as unknown as Promise<
      AdminClientModel[]
    >,
};

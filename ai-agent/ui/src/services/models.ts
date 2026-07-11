import api from './index';

/**
 * 用户可选模型（来自管理端配置且启用的模型目录，不含敏感字段）。
 */
export interface ModelItem {
  modelId: string;
  modelName: string;
  /** 模型类型：openai / deepseek / claude 等，前端按此分组当作"厂商"。 */
  modelType: string;
}

export const modelCatalogApi = {
  list: () =>
    api.get<ModelItem[]>(`/api/agent/models`) as unknown as Promise<ModelItem[]>,
};

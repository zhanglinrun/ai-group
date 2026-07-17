import api from './index';

export const agentApi = {
  allModels: () => api.get(`/data/allModels`),
  previewData: (modelCode: string) => api.get(`/data/previewData?modelCode=${modelCode}`),
};

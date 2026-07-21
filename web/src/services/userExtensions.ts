import api from './index';

export type UserSkill = {
  name: string;
  description: string;
  content: string;
  enabled: boolean;
};

export type UserMcp = {
  id?: string;
  name: string;
  serverUrl: string;
  transportType: 'sse' | 'streamable_http';
  enabled: boolean;
  toolCount?: number;
};

export const userSkillApi = {
  list: () =>
    api.get<UserSkill[]>('/api/agent/extensions/skills') as unknown as Promise<UserSkill[]>,
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<UserSkill>('/api/agent/extensions/skills', form) as unknown as Promise<UserSkill>;
  },
  setEnabled: (name: string, enabled: boolean) =>
    api.put<UserSkill>(
      `/api/agent/extensions/skills/${encodeURIComponent(name)}/enabled?enabled=${enabled}`,
    ) as unknown as Promise<UserSkill>,
  delete: (name: string) =>
    api.delete<boolean>(
      `/api/agent/extensions/skills/${encodeURIComponent(name)}`,
    ) as unknown as Promise<boolean>,
};

export const userMcpApi = {
  list: () => api.get<UserMcp[]>('/api/agent/extensions/mcps') as unknown as Promise<UserMcp[]>,
  save: (config: UserMcp) =>
    api.post<UserMcp>('/api/agent/extensions/mcps', config) as unknown as Promise<UserMcp>,
  setEnabled: (id: string, enabled: boolean) =>
    api.put<UserMcp>(
      `/api/agent/extensions/mcps/${encodeURIComponent(id)}/enabled?enabled=${enabled}`,
    ) as unknown as Promise<UserMcp>,
  delete: (id: string) =>
    api.delete<boolean>(
      `/api/agent/extensions/mcps/${encodeURIComponent(id)}`,
    ) as unknown as Promise<boolean>,
};

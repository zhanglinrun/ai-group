import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import UserExtensions from './index';

vi.mock('@/services/userExtensions', () => ({
  userSkillApi: {
    list: vi.fn().mockResolvedValue([]),
  },
  userMcpApi: {
    list: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('antd', () => ({
  Modal: Object.assign(() => null, { confirm: vi.fn() }),
  Switch: () => null,
  message: {
    error: vi.fn(),
    success: vi.fn(),
  },
}));

vi.mock('lucide-react', () => ({
  FileArchive: () => null,
  PlugZap: () => null,
  Plus: () => null,
  RefreshCw: () => null,
  Trash2: () => null,
  Upload: () => null,
  Zap: () => null,
}));

describe('UserExtensions', () => {
  it('只呈现当前用户自己的扩展管理语义', () => {
    const html = renderToStaticMarkup(<UserExtensions />);

    expect(html).toContain('我的扩展');
    expect(html).toContain('我的 Skills 与 MCP');
    expect(html).toContain('暂无 Skill');
    expect(html).not.toContain('系统 Skill');
    expect(html).not.toContain('系统 MCP');
  });
});

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SystemExtensionsPanel from './SystemExtensionsPanel';

vi.mock('@/services/admin', () => ({
  adminApi: {
    listSystemSkills: vi.fn().mockResolvedValue([]),
    listSystemMcps: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('antd', () => ({
  Modal: Object.assign(() => null, { confirm: vi.fn() }),
  Switch: () => null,
  message: { error: vi.fn(), success: vi.fn() },
}));

vi.mock('lucide-react', () => ({
  ClipboardCheck: () => null,
  ExternalLink: () => null,
  FileArchive: () => null,
  ListTree: () => null,
  Plus: () => null,
  Trash2: () => null,
  Upload: () => null,
}));

describe('SystemExtensionsPanel', () => {
  it('明确展示系统扩展而不是用户扩展', () => {
    const html = renderToStaticMarkup(<SystemExtensionsPanel />);

    expect(html).toContain('系统扩展');
    expect(html).toContain('系统 Skills');
    expect(html).toContain('不会展示在用户端');
    expect(html).toContain('Tool Registry');
    expect(html).toContain('载入隔离 Demo 数据');
    expect(html).not.toContain('我的 Skills 与 MCP');
  });
});

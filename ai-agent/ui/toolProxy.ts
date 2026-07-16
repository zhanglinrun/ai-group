type ToolProxyConfig = {
  target: string;
  changeOrigin: boolean;
  headers?: Record<string, string>;
  rewrite: (path: string) => string;
};

function trimTrailingSlash(value?: string): string {
  return (value || '').trim().replace(/\/+$/, '');
}

function buildDefaultToolTarget(): string {
  return 'http://127.0.0.1:1601';
}

function parseToolBaseUrl(rawBaseUrl?: string): { target: string; basePath: string } {
  const normalized = trimTrailingSlash(rawBaseUrl);
  if (!normalized) {
    return {
      target: buildDefaultToolTarget(),
      basePath: '',
    };
  }

  try {
    const parsed = new URL(normalized);
    return {
      target: `${parsed.protocol}//${parsed.host}`,
      basePath: parsed.pathname === '/' ? '' : trimTrailingSlash(parsed.pathname),
    };
  } catch {
    return {
      target: buildDefaultToolTarget(),
      basePath: '',
    };
  }
}

/**
 * 本地开发统一通过 `/tool/*` 访问 reactor-tool，避免前端把预览链接当成站内路由。
 */
export function createToolProxyConfig(rawBaseUrl?: string, rawToken?: string): ToolProxyConfig {
  const { target, basePath } = parseToolBaseUrl(rawBaseUrl);
  const token = (rawToken || '').trim();

  return {
    target,
    changeOrigin: true,
    ...(token ? { headers: { 'X-Tool-Token': token } } : {}),
    rewrite: (path: string) => {
      const normalizedPath = path.startsWith('/tool') ? path.slice('/tool'.length) || '/' : path;
      return `${basePath}${normalizedPath}`;
    },
  };
}

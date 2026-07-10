import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import tailwindcss from '@tailwindcss/vite';
import { createToolProxyConfig } from './toolProxy';

export default defineConfig(({ mode }) => {
  const repoRoot = path.resolve(__dirname, '../../..');
  const env = loadEnv(mode, repoRoot, '');
  const apiTarget = env.VITE_API_TARGET || 'http://127.0.0.1:8080';
  const agentBase = env.SERVICE_BASE_URL || apiTarget;
  const toolBase =
    env.REACTOR_TOOL_BASE_URL || env.AGENT_GROUP_REACTOR_TOOL_BASE_URL || 'http://127.0.0.1:1601';

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        crypto: 'crypto-browserify',
        'use-sync-external-store/shim': path.resolve(
          __dirname,
          'src/shims/use-sync-external-store/shim.ts',
        ),
        'use-sync-external-store/shim/with-selector': path.resolve(
          __dirname,
          'src/shims/use-sync-external-store/with-selector.ts',
        ),
      },
    },
    css: { preprocessorOptions: { less: { javascriptEnabled: true } } },
    optimizeDeps: {
      exclude: ['clsx', 'nanoid', 'radix-ui', 'lucide-react', 'tailwind-merge'],
    },
    server: {
      // true = 监听所有网卡，兼容 localhost(IPv6) 与 127.0.0.1(IPv4)
      host: true,
      port: 5173,
      strictPort: true,
      open: '/login',
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/web': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/tool': createToolProxyConfig(toolBase),
      },
    },
    define: {
      SERVICE_BASE_URL: JSON.stringify(agentBase),
      REACTOR_TOOL_BASE_URL: JSON.stringify(toolBase),
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      minify: 'terser' as const,
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          manualChunks(id) {
            const normalizedId = id.replace(/\\/g, '/');
            if (!normalizedId.includes('/node_modules/')) {
              return undefined;
            }
            if (/[\\/]node_modules[\\/](react|react-dom|react-router-dom)[\\/]/.test(id)) {
              return 'vendor-react';
            }
            if (
              normalizedId.includes('/node_modules/antd/') ||
              normalizedId.includes('/node_modules/@ant-design/')
            ) {
              return 'vendor-antd';
            }
            if (normalizedId.includes('/node_modules/echarts/')) {
              return 'vendor-echarts';
            }
            if (
              normalizedId.includes('/node_modules/react-markdown/') ||
              normalizedId.includes('/node_modules/remark-') ||
              normalizedId.includes('/node_modules/rehype-') ||
              normalizedId.includes('/node_modules/streamdown/') ||
              normalizedId.includes('/node_modules/unified/')
            ) {
              return 'vendor-markdown';
            }
            if (
              normalizedId.includes('/node_modules/@radix-ui/') ||
              normalizedId.includes('/node_modules/radix-ui/') ||
              normalizedId.includes('/node_modules/@base-ui/') ||
              normalizedId.includes('/node_modules/lucide-react/') ||
              normalizedId.includes('/node_modules/motion/')
            ) {
              return 'vendor-ui';
            }
            return undefined;
          },
        },
      },
    },
  };
});

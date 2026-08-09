import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";

import { pushToast } from "@/components/ui/toaster";

interface QueryMetaWithToast {
  errorToast?: boolean;
}

function shouldShowErrorToast(meta: unknown): boolean {
  if (meta === null || typeof meta !== "object") {
    return true;
  }
  return (meta as QueryMetaWithToast).errorToast !== false;
}

export function resolveApiErrorMessage(error: unknown): string {
  if (!(error instanceof Error)) {
    return "发生未知错误，请稍后重试。";
  }
  const normalized = error.message.trim();
  if (
    normalized === "Network Error" ||
    normalized.includes("ERR_NETWORK") ||
    normalized.includes("ECONNREFUSED")
  ) {
    return "无法连接 Gateway。请确认 Docker Compose 已启动，并检查 http://localhost:5173 是否可以访问。";
  }
  return error.message;
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      if (!shouldShowErrorToast(query.meta)) {
        return;
      }
      pushToast({
        title: "数据请求失败",
        description: resolveApiErrorMessage(error),
        variant: "danger",
        durationMs: 6000,
      });
    },
  }),
  mutationCache: new MutationCache({
    onError: (error, _variables, _context, mutation) => {
      if (!shouldShowErrorToast(mutation.meta)) {
        return;
      }
      pushToast({
        title: "操作执行失败",
        description: resolveApiErrorMessage(error),
        variant: "danger",
        durationMs: 6000,
      });
    },
  }),
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

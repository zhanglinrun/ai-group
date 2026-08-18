import { useEffect } from "react";
import { isRouteErrorResponse, Link, useRouteError } from "react-router-dom";

const RELOAD_KEY = "ai-group.stale-chunk-reload";

function errorMessage(error: unknown): string {
  if (isRouteErrorResponse(error)) {
    return error.statusText || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error ?? "未知错误");
}

function isStaleChunkError(error: unknown): boolean {
  const message = errorMessage(error);
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed/i.test(message);
}

export function RouteErrorPage(): JSX.Element {
  const error = useRouteError();
  const stale = isStaleChunkError(error);

  useEffect(() => {
    if (!stale) return;
    const alreadyReloaded = sessionStorage.getItem(RELOAD_KEY) === window.location.pathname;
    if (alreadyReloaded) return;
    sessionStorage.setItem(RELOAD_KEY, window.location.pathname);
    window.location.reload();
  }, [stale]);

  useEffect(() => {
    if (!stale) {
      sessionStorage.removeItem(RELOAD_KEY);
    }
  }, [stale]);

  if (stale) {
    return (
      <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center px-4">
        <section className="space-y-3 rounded-xl border border-border bg-surface p-6 text-sm">
          <h1 className="text-xl font-semibold">页面资源已更新</h1>
          <p className="text-foreground-muted">前端刚重新发布，正在刷新以加载最新的执行回放页面…</p>
          <Link className="text-primary hover:underline" to="/app">返回工作区首页</Link>
        </section>
      </main>
    );
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center px-4">
      <section className="space-y-3 rounded-xl border border-danger/35 bg-danger/10 p-6 text-sm">
        <h1 className="text-xl font-semibold text-danger">页面加载失败</h1>
        <p className="text-foreground-muted">{errorMessage(error)}</p>
        <div className="flex gap-4">
          <button className="text-primary hover:underline" type="button" onClick={() => window.location.reload()}>
            刷新页面
          </button>
          <Link className="text-primary hover:underline" to="/app">返回工作区首页</Link>
        </div>
      </section>
    </main>
  );
}

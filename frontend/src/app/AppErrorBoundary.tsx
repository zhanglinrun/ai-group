import type { ErrorInfo, ReactNode } from "react";
import { Component } from "react";

import { pushToast } from "@/components/ui/toaster";

interface AppErrorBoundaryProps {
  children: ReactNode;
}

interface AppErrorBoundaryState {
  hasError: boolean;
}

export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  public constructor(props: AppErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  public static getDerivedStateFromError(): AppErrorBoundaryState {
    return { hasError: true };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    console.error("frontend_unhandled_error", error, errorInfo);
    pushToast({
      title: "页面渲染异常",
      description: "已捕获错误，请刷新页面或返回工作区首页。",
      variant: "danger",
      durationMs: 4500,
    });
  }

  public render(): ReactNode {
    if (this.state.hasError) {
      return (
        <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center px-4">
          <section className="space-y-3 rounded-xl border border-danger/35 bg-danger/10 p-6 text-sm">
            <h1 className="text-xl font-semibold text-danger">页面加载失败</h1>
            <p className="text-muted-foreground">前端遇到未处理异常。你可以刷新页面，或返回工作区重新进入。</p>
            <div>
              <a className="text-primary hover:underline" href="/app">
                返回工作区首页
              </a>
            </div>
          </section>
        </main>
      );
    }

    return this.props.children;
  }
}

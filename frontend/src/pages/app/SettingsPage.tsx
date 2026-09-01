import { ExternalLink } from "lucide-react";

const LANGSMITH_CONSOLE_URL =
  import.meta.env.VITE_LANGSMITH_CONSOLE_URL?.trim() || "https://smith.langchain.com/";

export function SettingsPage(): JSX.Element {
  return (
    <section className="space-y-6">
      <header>
        <h1 className="text-h1 text-foreground">设置</h1>
        <p className="mt-1 text-caption text-foreground-muted">管理调研服务与账户配置。</p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2">
        <a
          aria-label="打开 LangSmith 控制台"
          className="group rounded-lg border border-white/[0.06] bg-surface p-5 transition-colors hover:border-foreground/20 hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          href={LANGSMITH_CONSOLE_URL}
        >
          <span className="flex items-center justify-between gap-3">
            <span className="text-caption font-medium text-foreground">Agent 观测与评估</span>
            <ExternalLink
              aria-hidden="true"
              className="h-4 w-4 shrink-0 text-foreground-muted transition-colors group-hover:text-foreground"
            />
          </span>
          <p className="mt-1 text-micro text-foreground-muted">运行追踪、调试与评测统一由 LangSmith 管理。</p>
          <span className="mt-4 inline-flex text-micro font-medium text-foreground">打开 LangSmith 控制台</span>
        </a>
        <div className="rounded-lg border border-white/[0.06] bg-surface p-5">
          <p className="text-caption font-medium text-foreground">账户与用量</p>
          <p className="mt-1 text-micro text-foreground-muted">即将上线。</p>
        </div>
      </div>
    </section>
  );
}

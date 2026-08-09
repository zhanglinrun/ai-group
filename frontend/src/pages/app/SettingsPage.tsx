import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";

export function SettingsPage(): JSX.Element {
  return (
    <section className="space-y-6">
      <header>
        <h1 className="text-h1 text-foreground">设置</h1>
        <p className="mt-1 text-caption text-foreground-muted">管理 Skill 审核与账户配置。</p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-white/[0.06] bg-surface p-5">
          <p className="text-caption font-medium text-foreground">Skill 管理</p>
          <p className="mt-1 text-micro text-foreground-muted">审核 AI 自动生成的规则候选。</p>
          <Button asChild size="sm" variant="secondary" className="mt-3">
            <Link to="/app/settings/skill-admin">进入审核台</Link>
          </Button>
        </div>
        <div className="rounded-lg border border-white/[0.06] bg-surface p-5">
          <p className="text-caption font-medium text-foreground">账户与用量</p>
          <p className="mt-1 text-micro text-foreground-muted">即将上线。</p>
        </div>
      </div>
    </section>
  );
}

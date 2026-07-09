import { memo } from "react";
import { Droplets, Lock, Wallet, Zap } from "lucide-react";
import type { AccountSummary } from "@/services/bff";

type QuotaOverviewProps = {
  summary: AccountSummary;
};

const quotaItems = [
  {
    key: "available",
    label: "可用配额",
    icon: Zap,
    accent: "text-violet-600",
    soft: "bg-violet-50",
    getValue: (s: AccountSummary) => s.availableQuota ?? 0,
  },
  {
    key: "period",
    label: "周期额度",
    icon: Wallet,
    accent: "text-sky-600",
    soft: "bg-sky-50",
    getValue: (s: AccountSummary) => s.periodQuotaBalance ?? 0,
  },
  {
    key: "topup",
    label: "加油包余额",
    icon: Droplets,
    accent: "text-emerald-600",
    soft: "bg-emerald-50",
    getValue: (s: AccountSummary) => s.topupQuotaBalance ?? 0,
  },
  {
    key: "frozen",
    label: "冻结中",
    icon: Lock,
    accent: "text-amber-600",
    soft: "bg-amber-50",
    getValue: (s: AccountSummary) => s.frozenBalance ?? 0,
  },
] as const;

const QuotaOverview = memo(({ summary }: QuotaOverviewProps) => (
  <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
    {quotaItems.map((item) => {
      const Icon = item.icon;
      return (
        <article
          key={item.key}
          className="rounded-3xl border border-[var(--chat-border)] bg-white/80 p-5 shadow-[var(--shadow-sm)] backdrop-blur-sm dark:bg-white/5"
        >
          <div className="flex items-center gap-3">
            <div
              className={`flex h-10 w-10 items-center justify-center rounded-2xl ${item.soft}`}
            >
              <Icon className={`h-5 w-5 ${item.accent}`} />
            </div>
            <div>
              <div className="text-sm text-[var(--chat-text-soft)]">{item.label}</div>
              <div className="mt-1 text-2xl font-semibold tracking-tight">
                {item.getValue(summary)} 点
              </div>
            </div>
          </div>
        </article>
      );
    })}
  </section>
));

QuotaOverview.displayName = "QuotaOverview";

export default QuotaOverview;

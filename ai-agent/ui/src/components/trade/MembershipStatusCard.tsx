import { memo } from "react";
import { Crown, Sparkles } from "lucide-react";
import type { AccountSummary } from "@/services/bff";
import { tierLabel } from "@/utils/tradeDisplay";

type MembershipStatusCardProps = {
  summary: AccountSummary;
};

const MembershipStatusCard = memo(({ summary }: MembershipStatusCardProps) => {
  const isPro = (summary.tier || "").toUpperCase() === "PRO";

  return (
    <section
      className={`overflow-hidden rounded-3xl border shadow-[var(--shadow-md)] ${
        isPro
          ? "border-violet-200/80 bg-gradient-to-br from-violet-600 via-violet-500 to-indigo-500 text-white"
          : "border-[var(--chat-border)] bg-[var(--chat-surface)]/90"
      }`}
    >
      <div className="p-6 sm:p-8">
        <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-white/15 px-3 py-1 text-xs font-medium backdrop-blur-sm">
              {isPro ? <Crown className="h-3.5 w-3.5" /> : <Sparkles className="h-3.5 w-3.5" />}
              <span>{tierLabel(summary.tier)}</span>
            </div>
            <h1 className="mt-4 font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight sm:text-4xl">
              {isPro ? "尊享 AI 对话权益" : "开启 Pro，解锁更高配额"}
            </h1>
            <p
              className={`mt-2 max-w-xl text-sm ${
                isPro ? "text-white/80" : "text-[var(--chat-text-soft)]"
              }`}
            >
              {isPro
                ? "你的会员已生效，可在对话中直接使用周期配额与加油包额度。"
                : "升级 Pro 会员或购买额度包，获得更充裕的模型调用配额。"}
            </p>
          </div>

          <div
            className={`grid min-w-[220px] grid-cols-2 gap-3 rounded-2xl p-4 ${
              isPro ? "bg-white/10 backdrop-blur-sm" : "bg-[var(--chat-surface-soft)]"
            }`}
          >
            <div>
              <div
                className={`text-xs ${isPro ? "text-white/70" : "text-[var(--chat-text-soft)]"}`}
              >
                生效时间
              </div>
              <div className="mt-1 text-sm font-medium">{summary.startAt || "-"}</div>
            </div>
            <div>
              <div
                className={`text-xs ${isPro ? "text-white/70" : "text-[var(--chat-text-soft)]"}`}
              >
                到期时间
              </div>
              <div className="mt-1 text-sm font-medium">{summary.expireAt || "-"}</div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
});

MembershipStatusCard.displayName = "MembershipStatusCard";

export default MembershipStatusCard;

import { memo } from 'react';
import { Gauge, Sparkles } from 'lucide-react';
import type { AccountSummary } from '@/services/bff';
import { formatMicroQuota } from '@/utils/tradeDisplay';

type QuotaStatusCardProps = {
  summary: AccountSummary;
};

const QuotaStatusCard = memo(({ summary }: QuotaStatusCardProps) => (
  <section className="overflow-hidden rounded-3xl border border-violet-200/80 bg-gradient-to-br from-violet-600 via-violet-500 to-indigo-500 text-white shadow-[var(--shadow-md)]">
    <div className="p-6 sm:p-8">
      <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="inline-flex items-center gap-2 rounded-full bg-white/15 px-3 py-1 text-xs font-medium backdrop-blur-sm">
            <Gauge className="h-3.5 w-3.5" />
            <span>按 Token 用量计费</span>
          </div>
          <h1 className="mt-4 font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight sm:text-4xl">
            当前可用 {formatMicroQuota(summary.availableQuota)}
          </h1>
          <p className="mt-2 max-w-xl text-sm text-white/80">
            输入每百万 Token 消耗 5 点，输出每百万 Token 消耗 30
            点；实际费率以调用模型的配置快照为准。
          </p>
        </div>

        <div className="min-w-[250px] rounded-2xl bg-white/10 p-4 backdrop-blur-sm">
          <div className="flex items-center gap-2 text-sm font-medium">
            <Sparkles className="h-4 w-4" />
            额度规则
          </div>
          <div className="mt-3 space-y-2 text-xs text-white/75">
            <div className="flex justify-between gap-4">
              <span>每月免费额度</span>
              <span>5 点，月底清零</span>
            </div>
            <div className="flex justify-between gap-4">
              <span>购买与拼团额度</span>
              <span>永久有效</span>
            </div>
            <div className="flex justify-between gap-4">
              <span>扣减顺序</span>
              <span>免费优先</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
));

QuotaStatusCard.displayName = 'QuotaStatusCard';

export default QuotaStatusCard;

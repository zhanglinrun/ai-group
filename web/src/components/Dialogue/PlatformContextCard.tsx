import type { FC } from 'react';
import { ArrowRight, BadgeDollarSign, Boxes, ReceiptText, WalletCards } from 'lucide-react';

import {
  parsePlatformContextTask,
  platformContextItems,
  type PlatformContextOperation,
} from '@/utils/platformContext';

const TITLES: Record<PlatformContextOperation, string> = {
  account_summary: '账户额度',
  pricing: '套餐价格',
  group_buy: '拼团进度',
  orders: '订单摘要',
};
const ICONS = {
  account_summary: WalletCards,
  pricing: BadgeDollarSign,
  group_buy: Boxes,
  orders: ReceiptText,
} satisfies Record<PlatformContextOperation, typeof WalletCards>;

export const PlatformContextCard: FC<{ task: CHAT.Task }> = ({ task }) => {
  const view = parsePlatformContextTask(task);
  if (!view) return null;

  const Icon = ICONS[view.operation];
  const items = platformContextItems(view);
  const incomplete = view.status !== 'COMPLETE' || !view.complete;

  return (
    <section
      aria-label={`platform-context-${view.operation}`}
      className="mt-2 overflow-hidden rounded-lg border border-[var(--chat-border)]/30 bg-[var(--chat-surface)]"
    >
      <div className="flex items-center justify-between gap-3 border-b border-[var(--chat-border)]/20 px-4 py-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <Icon className="size-4 shrink-0 text-[#0071e3]" />
          <h3 className="truncate text-sm font-semibold text-[var(--chat-text)]">
            {TITLES[view.operation]}
          </h3>
        </div>
        <span
          className={`text-xs font-medium ${incomplete ? 'text-amber-700' : 'text-emerald-700'}`}
        >
          {incomplete ? '数据可能不完整' : '已同步'}
        </span>
      </div>

      {items.length ? (
        <dl className="grid grid-cols-2 gap-px bg-[var(--chat-border)]/15 sm:grid-cols-4">
          {items.map((item) => (
            <div key={item.label} className="min-w-0 bg-[var(--chat-surface)] px-4 py-3">
              <dt className="text-xs text-[var(--chat-text-muted)]">{item.label}</dt>
              <dd className="mt-1 truncate text-sm font-semibold text-[var(--chat-text)]">
                {item.value}
              </dd>
            </div>
          ))}
        </dl>
      ) : null}

      {incomplete && view.message ? (
        <p className="border-t border-amber-200 bg-amber-50 px-4 py-2.5 text-xs leading-5 text-amber-800">
          {view.message}
        </p>
      ) : null}

      {view.cta ? (
        <div className="flex justify-end border-t border-[var(--chat-border)]/20 px-3 py-2">
          <a
            href={view.cta.path}
            className="inline-flex min-h-9 items-center gap-1.5 px-2 text-sm font-medium text-[#0071e3] hover:text-[#005bb5]"
          >
            {view.cta.label}
            <ArrowRight className="size-4" />
          </a>
        </div>
      ) : null}
    </section>
  );
};

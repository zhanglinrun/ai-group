import { memo } from 'react';

type StatCardProps = {
  label: string;
  value: string;
  warn?: boolean;
};

const StatCard = memo(({ label, value, warn }: StatCardProps) => (
  <div className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-5 shadow-[var(--shadow-sm)]">
    <div className="text-sm text-[var(--chat-text-soft)]">{label}</div>
    <div
      className={
        warn
          ? 'mt-2 text-xl font-semibold text-amber-600'
          : 'mt-2 text-xl font-semibold text-[var(--chat-text)]'
      }
    >
      {value}
    </div>
  </div>
));

StatCard.displayName = 'StatCard';

export default StatCard;

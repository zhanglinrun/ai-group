import { memo } from 'react';
import { Clock3, Cpu, Hash, RotateCcw, ShieldCheck } from 'lucide-react';
import { Badge } from '@/components/ui/badge';

type Props = {
  metrics?: CHAT.ChatItem['metrics'];
  className?: string;
};

function formatDuration(ms?: number): string | null {
  if (typeof ms !== 'number' || ms < 0) {
    return null;
  }
  if (ms < 1000) {
    return `${ms}ms`;
  }
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)}s`;
}

function formatTokens(tokens?: number): string | null {
  if (typeof tokens !== 'number' || tokens <= 0) {
    return null;
  }
  return `${tokens.toLocaleString()} tokens`;
}

/**
 * AI 回复下方的元数据 chips：模型 / tokens / 耗时。
 * 数据来自最终帧的 run 元数据；字段缺失则对应 chip 不渲染，整行为空时不渲染。
 */
const MessageMeta = memo(({ metrics, className }: Props) => {
  if (!metrics) {
    return null;
  }
  const duration = formatDuration(metrics.durationMs);
  const tokens = formatTokens(metrics.totalTokens);
  const model = metrics.modelName?.trim();
  const quality =
    typeof metrics.qualityScore === 'number' ? Math.round(metrics.qualityScore) : undefined;
  const replans = metrics.replanCount;

  if (!model && !tokens && !duration && quality === undefined && !replans) {
    return null;
  }

  const chipClassName =
    'gap-1 rounded-full bg-transparent px-2 py-0.5 text-[11px] font-medium text-[var(--chat-text-muted)] tabular-nums';

  return (
    <div className={['flex flex-wrap items-center gap-1.5', className].filter(Boolean).join(' ')}>
      {model ? (
        <Badge variant="secondary" className={chipClassName} title={`模型：${model}`}>
          <Cpu className="size-3" />
          <span className="max-w-[180px] truncate">{model}</span>
        </Badge>
      ) : null}
      {tokens ? (
        <Badge variant="secondary" className={chipClassName}>
          <Hash className="size-3" />
          {tokens}
        </Badge>
      ) : null}
      {duration ? (
        <Badge variant="secondary" className={chipClassName}>
          <Clock3 className="size-3" />
          {duration}
        </Badge>
      ) : null}
      {quality !== undefined ? (
        <Badge variant="secondary" className={chipClassName} title="Plan-Solve 最近一轮质量评分">
          <ShieldCheck className="size-3" />
          质量 {quality}
        </Badge>
      ) : null}
      {replans ? (
        <Badge variant="secondary" className={chipClassName} title="Evaluator 定向重规划次数">
          <RotateCcw className="size-3" />
          重规划 {replans}
        </Badge>
      ) : null}
    </div>
  );
});

MessageMeta.displayName = 'MessageMeta';

export default MessageMeta;

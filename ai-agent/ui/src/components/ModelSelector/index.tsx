import { useMemo } from 'react';
import { CheckIcon, ChevronDownIcon, SparklesIcon } from 'lucide-react';

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import type { ModelItem } from '@/services/models';

type Props = {
  models: ModelItem[];
  selectedModelId?: string;
  disabled?: boolean;
  onSelect: (modelId: string) => void;
};

const UNKNOWN_GROUP = '其他';

/** 按 modelType（当作厂商）分组，保持后端返回顺序。 */
function groupByProvider(models: ModelItem[]): Array<{ provider: string; items: ModelItem[] }> {
  const order: string[] = [];
  const map = new Map<string, ModelItem[]>();
  for (const model of models) {
    const provider = model.modelType?.trim() || UNKNOWN_GROUP;
    if (!map.has(provider)) {
      map.set(provider, []);
      order.push(provider);
    }
    map.get(provider)!.push(model);
  }
  return order.map((provider) => ({ provider, items: map.get(provider)! }));
}

const ModelSelector: ReactorType.FC<Props> = ({ models, selectedModelId, disabled, onSelect }) => {
  const hasModels = models.length > 0;
  const grouped = useMemo(() => groupByProvider(models), [models]);
  const selected = models.find((model) => model.modelId === selectedModelId);
  const label = selected?.modelName || (hasModels ? '默认模型' : '暂无模型');

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={disabled || !hasModels}
          className={cn(
            'group inline-flex h-9 max-w-full items-center gap-2 rounded-full border border-transparent px-3 text-[14px] font-medium transition-all duration-200',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
            disabled || !hasModels
              ? 'cursor-not-allowed opacity-50'
              : 'hover:bg-[var(--chat-surface-soft)]',
            selected ? 'bg-brand-soft text-brand' : 'bg-transparent text-[var(--chat-text)]',
          )}
        >
          <span
            className={cn(
              'flex size-[26px] shrink-0 items-center justify-center rounded-full transition-all duration-200',
              selected ? 'bg-brand-soft text-brand' : 'text-[var(--chat-text-soft)]',
            )}
          >
            <SparklesIcon className="size-4" />
          </span>
          <span className="max-w-[140px] truncate">{label}</span>
          <ChevronDownIcon className="size-4 shrink-0 text-[var(--chat-text-muted)]" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        side="bottom"
        sideOffset={12}
        className="max-h-[360px] w-[260px] overflow-y-auto rounded-[16px] border border-border bg-popover p-1 shadow-[0_10px_28px_-18px_rgba(15,23,42,0.2)]"
      >
        {grouped.map(({ provider, items }) => (
          <div key={provider} className="mb-1 last:mb-0">
            <div className="px-2 pb-1 pt-1 text-[10px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
              {provider}
            </div>
            <div className="space-y-0.5">
              {items.map((model) => {
                const active = model.modelId === selectedModelId;
                return (
                  <button
                    key={model.modelId}
                    type="button"
                    className={cn(
                      'flex w-full items-center gap-2 rounded-xl border border-transparent px-2 py-2 text-left transition-all duration-200',
                      active ? 'bg-brand-soft' : 'bg-transparent hover:bg-[var(--chat-surface-soft)]',
                    )}
                    onClick={() => onSelect(model.modelId)}
                  >
                    <span className="min-w-0 flex-1 truncate text-[14px] font-medium text-[var(--chat-text)]">
                      {model.modelName}
                    </span>
                    {active ? <CheckIcon className="size-3.5 shrink-0 text-brand" /> : null}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ModelSelector;

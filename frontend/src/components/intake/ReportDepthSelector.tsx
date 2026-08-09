import type { ReportDepth } from "@/api/types";
import { cn } from "@/lib/utils";

interface ReportDepthSelectorProps {
  value: ReportDepth;
  onChange: (depth: ReportDepth) => void;
  className?: string;
}

interface ReportDepthOption {
  id: ReportDepth;
  label: string;
  hint: string;
  devOnly?: boolean;
}

const REPORT_DEPTH_OPTIONS: ReportDepthOption[] = [
  { id: "quick", label: "Quick", hint: "平衡速度与质量" },
  { id: "deep", label: "Deep", hint: "更深分析与更高覆盖" },
  { id: "debug", label: "Debug", hint: "仅调试", devOnly: true },
];

export function ReportDepthSelector({
  value,
  onChange,
  className,
}: ReportDepthSelectorProps): JSX.Element {
  const visibleOptions = REPORT_DEPTH_OPTIONS.filter((option) => {
    if (!option.devOnly) {
      return true;
    }
    return import.meta.env.DEV;
  });

  return (
    <div
      aria-label="选择分析档位"
      className={cn(
        "inline-flex items-center gap-1 rounded-md border border-white/[0.08] bg-white/[0.03] p-1",
        className,
      )}
      role="tablist"
    >
      {visibleOptions.map((option) => {
        const isActive = option.id === value;
        return (
          <button
            aria-selected={isActive}
            className={cn(
              "rounded px-2.5 py-0.5 text-[11px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
              isActive
                ? "bg-primary/15 text-primary"
                : "text-foreground-muted hover:bg-white/[0.05] hover:text-foreground",
            )}
            key={option.id}
            onClick={() => onChange(option.id)}
            role="tab"
            title={option.devOnly ? `${option.label} · ${option.hint}` : option.hint}
            type="button"
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

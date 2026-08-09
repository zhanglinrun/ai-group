import { Link } from "react-router-dom";

import { cn } from "@/lib/utils";

type IntakeMode = "chat" | "expert";

interface IntakeModeSwitcherProps {
  active: IntakeMode;
  className?: string;
}

const MODES: Array<{ id: IntakeMode; label: string; to: string }> = [
  { id: "chat", label: "Agent 引导", to: "/app/runs/new" },
  { id: "expert", label: "专家表单", to: "/app/runs/new/expert" },
];

export function IntakeModeSwitcher({ active, className }: IntakeModeSwitcherProps): JSX.Element {
  return (
    <div
      aria-label="选择新建分析的方式"
      className={cn(
        "inline-flex items-center gap-1 rounded-md border border-white/[0.08] bg-white/[0.03] p-1",
        className,
      )}
      role="tablist"
    >
      {MODES.map((mode) => {
        const isActive = mode.id === active;
        return (
          <Link
            aria-selected={isActive}
            className={cn(
              "rounded px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
              isActive
                ? "bg-primary/15 text-primary"
                : "text-foreground-muted hover:bg-white/[0.05] hover:text-foreground",
            )}
            key={mode.id}
            role="tab"
            to={mode.to}
          >
            {mode.label}
          </Link>
        );
      })}
    </div>
  );
}

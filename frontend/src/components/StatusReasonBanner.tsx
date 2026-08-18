import { AlertTriangle, CircleSlash, CircleX } from "lucide-react";

import { cn } from "@/lib/utils";

const DEFAULT_REASONS: Record<string, string> = {
  degraded: "报告已完成，但存在已知质量缺口。",
  failed: "运行过程中发生错误。",
  cancelled: "你已停止此次调研。",
};

export interface StatusReasonBannerProps {
  status: string;
  reason?: string | null;
  className?: string;
}

export function StatusReasonBanner({
  status,
  reason,
  className,
}: StatusReasonBannerProps): JSX.Element | null {
  if (status !== "degraded" && status !== "failed" && status !== "cancelled") {
    return null;
  }
  const text = reason?.trim() || DEFAULT_REASONS[status];
  const isFailed = status === "failed";
  const isDegraded = status === "degraded";
  const Icon = isFailed ? CircleX : isDegraded ? AlertTriangle : CircleSlash;
  const label = isFailed ? "失败原因" : isDegraded ? "降级原因" : "停止原因";
  return (
    <div
      className={cn(
        "flex items-start gap-2 rounded-lg border px-3 py-2.5 text-caption",
        isFailed
          ? "border-danger/30 bg-danger/[0.06] text-danger"
          : isDegraded
            ? "border-warning/30 bg-warning/[0.08] text-warning"
            : "border-white/[0.08] bg-white/[0.03] text-foreground-muted",
        className,
      )}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <p>
        <span className="font-medium">{label}：</span>
        {text}
      </p>
    </div>
  );
}

import type { LucideIcon } from "lucide-react";
import {
  AlertTriangle,
  CheckCircle2,
  CircleDashed,
  CircleSlash,
  CircleX,
  LoaderCircle,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import type { BadgeProps } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

type StatusType = "running" | "completed" | "degraded" | "failed" | "cancelled" | string;

interface StatusMeta {
  icon: LucideIcon;
  label: string;
  variant: BadgeProps["variant"];
}

const STATUS_META: Record<string, StatusMeta> = {
  running: {
    icon: LoaderCircle,
    label: "进行中",
    variant: "warning",
  },
  completed: {
    icon: CheckCircle2,
    label: "完成",
    variant: "success",
  },
  degraded: {
    icon: AlertTriangle,
    label: "降级",
    variant: "warning",
  },
  failed: {
    icon: CircleX,
    label: "失败",
    variant: "danger",
  },
  cancelled: {
    icon: CircleSlash,
    label: "已停止",
    variant: "secondary",
  },
};

export interface StatusBadgeProps {
  status: StatusType;
  reason?: string | null;
}

export function StatusBadge({ status, reason }: StatusBadgeProps): JSX.Element {
  const meta = STATUS_META[status] ?? {
    icon: CircleDashed,
    label: status,
    variant: "secondary" as const,
  };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant} title={reason?.trim() ? reason : undefined}>
      <Icon className={cn("h-3 w-3", status === "running" && "animate-spin")} />
      {meta.label}
    </Badge>
  );
}

import { X } from "lucide-react";

import { cn } from "@/lib/utils";

export type ToastVariant = "default" | "success" | "warning" | "danger";

export interface ToastProps {
  title: string;
  description?: string;
  variant?: ToastVariant;
  className?: string;
  onDismiss?: () => void;
}

const VARIANT_CLASS: Record<ToastVariant, string> = {
  default: "border-border bg-card text-card-foreground",
  success: "border-success/40 bg-success/15 text-success-foreground",
  warning: "border-warning/40 bg-warning/15 text-warning-foreground",
  danger: "border-danger/40 bg-danger/15 text-danger-foreground",
};

export function Toast({
  title,
  description,
  variant = "default",
  className,
  onDismiss,
}: ToastProps): JSX.Element {
  return (
    <div
      className={cn(
        "flex gap-2 rounded-md border p-3 text-sm shadow-lg",
        VARIANT_CLASS[variant],
        className,
      )}
      role="alert"
    >
      <div className="min-w-0 flex-1">
        <p className="font-medium leading-snug">{title}</p>
        {description ? (
          <p className="mt-1 text-xs leading-relaxed text-foreground-muted">{description}</p>
        ) : null}
      </div>
      {onDismiss ? (
        <button
          type="button"
          aria-label="关闭提示"
          className="shrink-0 rounded p-0.5 text-foreground-muted transition-colors hover:bg-white/10 hover:text-foreground"
          onClick={onDismiss}
        >
          <X className="h-4 w-4" />
        </button>
      ) : null}
    </div>
  );
}

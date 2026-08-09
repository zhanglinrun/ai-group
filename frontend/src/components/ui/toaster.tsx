import { useSyncExternalStore } from "react";

import { Toast } from "@/components/ui/toast";

interface ToastItem {
  id: string;
  title: string;
  description?: string;
  variant?: "default" | "success" | "warning" | "danger";
  durationMs: number;
}

const listeners = new Set<() => void>();
let items: ToastItem[] = [];

function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): ToastItem[] {
  return items;
}

function removeToast(id: string): void {
  items = items.filter((item) => item.id !== id);
  emit();
}

export interface PushToastInput {
  title: string;
  description?: string;
  variant?: "default" | "success" | "warning" | "danger";
  durationMs?: number;
}

export function pushToast(input: PushToastInput): void {
  const toastItem: ToastItem = {
    id: `toast_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    title: input.title,
    description: input.description,
    variant: input.variant ?? "default",
    durationMs: input.durationMs ?? 3500,
  };
  items = [toastItem, ...items].slice(0, 4);
  emit();

  window.setTimeout(() => {
    removeToast(toastItem.id);
  }, toastItem.durationMs);
}

export function Toaster(): JSX.Element {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed bottom-4 right-4 z-[100] flex w-[min(380px,calc(100vw-2rem))] flex-col-reverse gap-2"
    >
      {snapshot.map((item) => (
        <div className="pointer-events-auto" key={item.id}>
          <Toast
            description={item.description}
            title={item.title}
            variant={item.variant}
            onDismiss={() => {
              removeToast(item.id);
            }}
          />
        </div>
      ))}
    </div>
  );
}

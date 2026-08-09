import { type ReactNode, useEffect, useState } from "react";

import { usePatchWatchlistItem } from "@/api/hooks";
import type { WatchlistDigestItemResponse } from "@/api/types";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { NativeSelect } from "@/components/ui/native-select";
import { pushToast } from "@/components/ui/toaster";
import { formatDateTime } from "@/lib/format";

type FrequencyKey = "manual" | "daily" | "weekly" | "biweekly";

const FREQUENCY_OPTIONS: { value: FrequencyKey; label: string; hours: number | null }[] = [
  { value: "manual", label: "手动", hours: null },
  { value: "daily", label: "每日", hours: 24 },
  { value: "weekly", label: "每周", hours: 24 * 7 },
  { value: "biweekly", label: "每两周", hours: 24 * 14 },
];

function hoursToFrequency(hours: number | null): FrequencyKey {
  if (hours === null) return "manual";
  if (hours <= 24) return "daily";
  if (hours <= 24 * 7) return "weekly";
  return "biweekly";
}

interface RefreshScheduleDialogProps {
  item: WatchlistDigestItemResponse;
  children: ReactNode;
}

export function RefreshScheduleDialog({ item, children }: RefreshScheduleDialogProps): JSX.Element {
  const [open, setOpen] = useState(false);
  const [frequency, setFrequency] = useState<FrequencyKey>(
    hoursToFrequency(item.refresh_interval_hours),
  );
  const patchMutation = usePatchWatchlistItem();

  useEffect(() => {
    if (open) {
      setFrequency(hoursToFrequency(item.refresh_interval_hours));
    }
  }, [item.refresh_interval_hours, item.watch_id, open]);

  async function handleSave(): Promise<void> {
    const selected = FREQUENCY_OPTIONS.find((o) => o.value === frequency);
    try {
      await patchMutation.mutateAsync({
        watchId: item.watch_id,
        payload: { refresh_interval_hours: selected?.hours ?? null },
      });
      pushToast({ title: "刷新计划已更新", variant: "success" });
      setOpen(false);
    } catch (error) {
      if (error instanceof Error)
        pushToast({ title: "更新失败", description: error.message, variant: "danger" });
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>刷新计划 · {item.competitor_id}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-1">
          <div className="space-y-1.5">
            <label className="text-caption text-foreground-muted" htmlFor="freq-select">
              刷新频率
            </label>
            <NativeSelect
              id="freq-select"
              className="w-full"
              value={frequency}
              onChange={(e) => setFrequency(e.target.value as FrequencyKey)}
            >
              {FREQUENCY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </NativeSelect>
          </div>

          {item.last_refreshed_at ? (
            <p className="text-micro text-foreground-subtle">
              上次刷新：{formatDateTime(item.last_refreshed_at)}
            </p>
          ) : null}

          {item.next_refresh_at ? (
            <p className="text-micro text-foreground-subtle">
              下次刷新：{formatDateTime(item.next_refresh_at)}
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => setOpen(false)} disabled={patchMutation.isPending}>
            取消
          </Button>
          <Button onClick={() => void handleSave()} disabled={patchMutation.isPending}>
            {patchMutation.isPending ? "保存中…" : "保存"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

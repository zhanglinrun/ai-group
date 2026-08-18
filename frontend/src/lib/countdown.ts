/** Pay-side unpaid close window; matches Alipay timeout_express and TimeoutCloseOrderJob. */
export const PAYMENT_WINDOW_MS = 30 * 60 * 1000;

/** Seed / schema fallback when the activity does not send validTime. */
export const DEFAULT_GROUP_VALID_MINUTES = 1440;

export function parseTime(value: string | number | Date | null | undefined): number | null {
  if (value == null || value === "") return null;
  if (value instanceof Date) {
    const ms = value.getTime();
    return Number.isFinite(ms) ? ms : null;
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return value < 1e12 ? value * 1000 : value;
  }
  const raw = String(value).trim();
  if (!raw) return null;
  if (/^\d+$/.test(raw)) {
    const n = Number(raw);
    return n < 1e12 ? n * 1000 : n;
  }
  const local = raw.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})/);
  if (local && !/[zZ]|[+-]\d{2}:?\d{2}$/.test(raw)) {
    const ms = new Date(`${local[1]}T${local[2]}`).getTime();
    return Number.isFinite(ms) ? ms : null;
  }
  const ms = Date.parse(raw);
  return Number.isFinite(ms) ? ms : null;
}

export function secondsUntil(deadline: string | number | Date | null | undefined, now = Date.now()): number | null {
  const ms = parseTime(deadline);
  if (ms == null) return null;
  return Math.max(0, Math.floor((ms - now) / 1000));
}

export function formatCountdown(seconds: number | null | undefined): string {
  if (seconds == null) return "";
  const safe = Math.max(0, Math.floor(seconds));
  if (safe <= 0) return "已过期";
  const days = Math.floor(safe / 86400);
  const hours = Math.floor((safe % 86400) / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const rest = safe % 60;
  if (days > 0) return `${days}天${hours}小时${minutes}分`;
  if (hours > 0) return `${hours}小时${minutes}分${String(rest).padStart(2, "0")}秒`;
  return `${minutes}分${String(rest).padStart(2, "0")}秒`;
}

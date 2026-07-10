import { useEffect, useState } from 'react';

export const COUNTDOWN_ENDED = '已结束';

function formatRemaining(ms: number): string {
  if (ms <= 0) {
    return COUNTDOWN_ENDED;
  }
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}

/**
 * 拼团倒计时：按结束时间在前端每秒实时递减。
 * 后端返回的 validTimeCountdown 是查询瞬间算好的静态字符串（页面上永远不动），
 * 有 validEndTime 时优先用它本地跳秒，没有时回退静态串。
 */
export function useCountdown(endTime?: string | number | null, fallback?: string): string {
  const end = endTime ? new Date(endTime).getTime() : Number.NaN;
  const valid = Number.isFinite(end);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!valid) {
      return;
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [valid, endTime]);

  if (!valid) {
    return fallback || '';
  }
  return formatRemaining(end - now);
}

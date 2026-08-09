export interface AnalyticsPayload {
  event: string;
  props?: Record<string, unknown>;
}

export function track(event: string, props?: Record<string, unknown>): void {
  const payload: AnalyticsPayload = {
    event,
    props,
  };
  console.debug("analytics.track", payload);
}

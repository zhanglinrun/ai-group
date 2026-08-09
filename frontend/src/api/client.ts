import axios from "axios";

// All Agent calls go through Java BFF. The browser never knows the Python
// service address and the BFF can attach the verified Gateway identity.
const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "" : "";

const resolvedBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim();
export const API_BASE_URL =
  resolvedBaseUrl !== undefined && resolvedBaseUrl.length > 0
    ? resolvedBaseUrl
    : DEFAULT_API_BASE_URL;

interface ApiErrorPayload {
  error_code?: string;
  message?: string;
  detail?: string;
}

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30_000,
  withCredentials: true,
});

apiClient.interceptors.request.use((config) => {
  const url = config.url ?? "";
  const shouldProxyAgent =
    url.startsWith("/api/runs") ||
    url.startsWith("/api/watchlist") ||
    url.startsWith("/api/demo-fixtures") ||
    url.startsWith("/api/skill-candidates");
  if (shouldProxyAgent) {
    config.url = `/api/bff/agent${url.substring("/api".length)}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => {
    // The Java BFF keeps downstream failures in a HTTP 200 envelope.  Treat
    // those envelopes as rejected requests; otherwise a page may interpret
    // `{ code: 500, data: null }` as a successful Agent DTO and crash while
    // rendering fields such as `user_query` or `run_id`.
    const payload = response.data as Record<string, unknown> | undefined;
    if (
      payload &&
      typeof payload === "object" &&
      payload.code !== undefined &&
      Number(payload.code) !== 200 &&
      (payload.data === null || payload.data === undefined)
    ) {
      const message = typeof payload.message === "string" && payload.message.trim()
        ? payload.message
        : "请求失败，请稍后重试";
      return Promise.reject(new Error(message));
    }
    return response;
  },
  (error: unknown) => {
    if (!axios.isAxiosError(error)) {
      return Promise.reject(error);
    }
    const payload = error.response?.data as ApiErrorPayload | undefined;
    if (payload?.error_code && payload?.message) {
      return Promise.reject(new Error(`[${payload.error_code}] ${payload.message}`));
    }
    if (payload?.message) {
      return Promise.reject(new Error(payload.message));
    }
    if (payload?.detail) {
      return Promise.reject(new Error(payload.detail));
    }
    if (error.message) {
      return Promise.reject(new Error(error.message));
    }
    return Promise.reject(new Error("Unknown API error"));
  },
);

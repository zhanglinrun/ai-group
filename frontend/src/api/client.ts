import axios from "axios";

// Agent JSON goes through Gateway `/api/runs/**` (and watchlist / skill / fixtures).
// The browser never receives the Python service address.
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

apiClient.interceptors.response.use(
  (response) => {
    // Java Result envelopes keep business failures in HTTP 200. Treat those as
    // rejected requests so pages never render `{ code: 500, data: null }` as an
    // Agent DTO. FastAPI errors use real status codes and skip this branch.
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

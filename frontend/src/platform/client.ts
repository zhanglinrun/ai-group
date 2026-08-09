import axios from "axios";

interface PlatformEnvelope {
  code?: number | string;
  message?: string;
  data?: unknown;
}

function platformErrorMessage(payload: PlatformEnvelope | undefined): string {
  const code = Number(payload?.code);
  if (code === 608) return "用户名或密码错误，请确认账号已注册";
  if (code === 607) return "用户名已存在，请直接登录";
  if (code === 401 || code === 604 || code === 605) return "登录状态已失效，请重新登录";
  return payload?.message || "请求失败";
}

export const platformClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL?.trim() || "",
  timeout: 20_000,
  withCredentials: true,
});

platformClient.interceptors.response.use(
  (response) => {
    // The Java services keep business failures in a HTTP 200 Result envelope.
    // Reject those responses here so callers never dereference a null data
    // object (for example, login failures return data: null).
    const payload = response.data as PlatformEnvelope | undefined;
    if (payload && typeof payload === "object" && typeof payload.code === "number" && payload.code !== 200) {
      return Promise.reject(new Error(platformErrorMessage(payload)));
    }
    return response;
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const payload = error.response?.data as PlatformEnvelope | undefined;
      const message = payload ? platformErrorMessage(payload) : undefined;
      return Promise.reject(new Error(message || error.message || "请求失败"));
    }
    return Promise.reject(error);
  },
);

export interface PlatformUser {
  id: number;
  username: string;
  role: "USER" | "ADMIN" | string;
  email?: string | null;
}

export interface QuotaSummary {
  userId?: number;
  freeQuotaBalance?: number;
  paidQuotaBalance?: number;
  frozenBalance?: number;
  availableQuota?: number;
}

export async function currentUser(): Promise<PlatformUser> {
  const { data } = await platformClient.get<{ data: PlatformUser }>("/api/auth/me");
  return data.data;
}

export async function login(username: string, password: string): Promise<PlatformUser> {
  const { data } = await platformClient.post<{ data?: { user?: PlatformUser } | null }>("/api/auth/login", {
    username,
    password,
  });
  const user = data.data?.user;
  if (!user) {
    throw new Error("登录失败，请确认账号已注册且密码正确");
  }
  return user;
}

export async function register(payload: { username: string; password: string; email?: string }): Promise<void> {
  await platformClient.post("/api/auth/register", payload);
}

export async function logout(): Promise<void> {
  await platformClient.post("/api/auth/logout");
}

export async function accountOverview(): Promise<QuotaSummary & { quotaLedger?: Array<Record<string, unknown>> }> {
  const { data } = await platformClient.get<{ data: QuotaSummary & { quotaLedger?: Array<Record<string, unknown>> } }>(
    "/api/bff/account/summary",
  );
  return data.data;
}

export async function pricing(): Promise<Record<string, unknown>> {
  const { data } = await platformClient.get<{ data: Record<string, unknown> }>("/api/bff/pricing");
  return data.data;
}

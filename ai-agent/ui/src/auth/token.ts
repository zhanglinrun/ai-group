const ACCESS_TOKEN_KEY = "ai_group_access_token";
const REFRESH_TOKEN_KEY = "ai_group_refresh_token";
const USER_ID_KEY = "ai_group_user_id";
const USERNAME_KEY = "ai_group_username";

export function getAccessToken(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setAuthTokens(accessToken: string, refreshToken?: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  if (refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
}

export function setAuthUser(userId: number, username?: string): void {
  localStorage.setItem(USER_ID_KEY, String(userId));
  if (username) {
    localStorage.setItem(USERNAME_KEY, username);
  }
}

export function getAuthUserId(): number | null {
  if (typeof window === "undefined") {
    return null;
  }
  const raw = localStorage.getItem(USER_ID_KEY);
  if (!raw) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function clearAuthTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_ID_KEY);
  localStorage.removeItem(USERNAME_KEY);
}

export function isAuthenticated(): boolean {
  return Boolean(getAccessToken());
}

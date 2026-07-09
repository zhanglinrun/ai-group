import api from "./index";
import { setAuthTokens, setAuthUser, clearAuthTokens } from "@/auth/token";

export interface UserProfile {
  id: number;
  username: string;
  email?: string;
  phone?: string;
  role?: string;
  status?: number;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken?: string;
  user: UserProfile;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
  phone?: string;
}

export const authApi = {
  register: (payload: RegisterRequest) =>
    api.post<UserProfile>("/api/auth/register", payload) as unknown as Promise<UserProfile>,

  login: (payload: LoginRequest) =>
    api.post<LoginResponse>("/api/auth/login", payload) as unknown as Promise<LoginResponse>,

  logout: async () => {
    try {
      await api.post<void>("/api/auth/logout");
    } catch {
      // ignore network errors during logout
    } finally {
      clearAuthTokens();
    }
  },

  profile: () =>
    api.get<UserProfile>("/api/auth/profile") as unknown as Promise<UserProfile>,

  persistLogin: (response: LoginResponse) => {
    setAuthTokens(response.accessToken, response.refreshToken);
    if (response.user?.id != null) {
      setAuthUser(response.user.id, response.user.username);
    }
  },
};

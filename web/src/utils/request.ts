import axios, { AxiosInstance, AxiosResponse } from 'axios';
import { getAccessToken, getRefreshToken, clearAuthTokens, setAuthTokens } from '@/auth/token';
import { ROUTES } from '@/router/routes';
import { showMessage } from './utils';
import { getDeviceId } from '@/services/agentConversation';
import { resolveServiceBaseUrl } from './origin';

const apiBaseUrl = resolveServiceBaseUrl(
  import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_API_TARGET || '',
);

// 创建axios实例
const request: AxiosInstance = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

// Bare client for token refresh to avoid interceptor recursion.
const refreshClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    // 兼容仍然依赖设备标识的上传与流式接口
    config.headers['X-Device-Id'] = getDeviceId();
    const token = getAccessToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    console.error('请求错误:', error);
    return Promise.reject(error);
  },
);

let isRefreshing = false;
let refreshWaiters: Array<(token: string | null) => void> = [];

const notifyRefreshWaiters = (token: string | null) => {
  refreshWaiters.forEach((cb) => cb(token));
  refreshWaiters = [];
};

const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  try {
    const resp = await refreshClient.post('/api/auth/refresh', { refreshToken });
    const data = resp?.data;
    const payload = data?.data ?? data;
    if (!payload?.accessToken) {
      return null;
    }
    setAuthTokens(payload.accessToken, payload.refreshToken);
    return payload.accessToken;
  } catch {
    return null;
  }
};

const noAuth = (url?: string) => {
  clearAuthTokens();
  showMessage()?.error('未登录');
  const loginPath = url || ROUTES.LOGIN;
  if (!location.pathname.startsWith(ROUTES.LOGIN)) {
    location.href = loginPath;
  }
};

// 响应拦截器
request.interceptors.response.use(
  (response: AxiosResponse) => {
    const { data, status } = response;

    if (status === 200) {
      // 根据后端约定的数据结构处理
      // 兼容两种响应格式: {code:200, msg, data} 和 {code:"0000", info, data}
      if (data.code === 200 || data.code === '0000') {
        return data.data;
      } else if (data.code === 401 || data.code === '0003') {
        noAuth(data.redirectUrl);
      } else {
        const errMsg = data.msg || data.info || '请求失败';
        showMessage()?.error(errMsg);
        return Promise.reject(new Error(errMsg));
      }
    }

    return response;
  },
  async (error) => {
    console.error('响应错误:', error);

    const message = showMessage();
    if (error.response) {
      const { status, data: resData } = error.response;

      switch (status) {
        case 401:
          // 尝试使用 refresh token 换取新 access token 并重放请求（仅一次）
          if (error.config && !error.config.__isRetryRequest) {
            let retryToken: string | null = null;
            if (!isRefreshing) {
              isRefreshing = true;
              const next = await refreshAccessToken();
              isRefreshing = false;
              notifyRefreshWaiters(next);
              if (!next) {
                noAuth(resData?.redirectUrl);
                break;
              }
              // The first request that triggers refresh should retry immediately.
              retryToken = next;
            } else {
              retryToken = await new Promise<string | null>((resolve) => {
                refreshWaiters.push(resolve);
              });
            }
            if (retryToken && error.config) {
              error.config.__isRetryRequest = true;
              error.config.headers = error.config.headers || {};
              error.config.headers.Authorization = `Bearer ${retryToken}`;
              return request(error.config);
            }
          }
          noAuth(resData?.redirectUrl);
          break;
        case 403:
          message?.error(error.message || '没有权限访问');
          break;
        case 404:
          message?.error(error.message || '请求的资源不存在');
          break;
        case 500:
          message?.error(error.message || '服务器内部错误');
          break;
        default:
          message?.error(error.message || `请求失败，状态码: ${status}`);
      }
    } else if (error.request) {
      message?.error(error.message || '网络错误，请检查网络连接');
    } else {
      message?.error('请求配置错误');
    }

    return Promise.reject(error);
  },
);

export default request;

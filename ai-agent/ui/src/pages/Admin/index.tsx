import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { message } from 'antd';
import { KeyRound, Loader2, LogOut, Package, RefreshCw, ShieldCheck, Users } from 'lucide-react';
import { authApi } from '@/services/auth';
import {
  adminApi,
  type AdminClientApi,
  type AdminClientModel,
  type AdminGroupActivity,
  type AdminSku,
} from '@/services/admin';
import { clearAuthTokens, isAuthenticated } from '@/auth/token';
import { ROUTES } from '@/router/routes';

type AdminTab = 'skus' | 'groupbuy' | 'models';

const ADMIN_ROLE_KEY = 'ai_group_role';

function getStoredRole(): string | null {
  return typeof window === 'undefined' ? null : localStorage.getItem(ADMIN_ROLE_KEY);
}

const inputClass =
  'w-full rounded-lg border border-[var(--chat-border)] bg-white/80 px-2 py-1.5 text-sm outline-none focus:border-[var(--primary)] dark:bg-white/10';
const thClass = 'px-3 py-2 text-left text-xs font-medium text-[var(--chat-text-soft)]';
const tdClass = 'px-3 py-2 align-middle';

/** 管理员登录卡片：普通登录接口 + 校验角色必须为 ADMIN */
const AdminLogin = memo(({ onLogin }: { onLogin: () => void }) => {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = useCallback(async () => {
    if (!username.trim() || !password) {
      message.warning('请输入管理员账号与密码');
      return;
    }
    setLoading(true);
    try {
      const resp = await authApi.login({ username: username.trim(), password });
      const role = resp.user?.role || '';
      if (role.toUpperCase() !== 'ADMIN') {
        message.error('该账号不是管理员');
        return;
      }
      authApi.persistLogin(resp);
      localStorage.setItem(ADMIN_ROLE_KEY, role.toUpperCase());
      onLogin();
    } catch (err) {
      console.error('管理员登录失败', err);
    } finally {
      setLoading(false);
    }
  }, [username, password, onLogin]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--page-gradient)] px-4">
      <div className="w-full max-w-sm rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/95 p-8 shadow-[var(--shadow-md)]">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-[var(--chat-text)] text-white">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <div>
            <div className="text-lg font-semibold">运营端登录</div>
            <div className="text-xs text-[var(--chat-text-soft)]">仅限 ADMIN 角色账号</div>
          </div>
        </div>
        <label className="mb-1 block text-xs text-[var(--chat-text-soft)]">用户名</label>
        <input
          className={`${inputClass} mb-3`}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="admin"
        />
        <label className="mb-1 block text-xs text-[var(--chat-text-soft)]">密码</label>
        <input
          className={`${inputClass} mb-5`}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void submit()}
          placeholder="管理员密码"
        />
        <button
          type="button"
          disabled={loading}
          onClick={() => void submit()}
          className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-[var(--chat-text)] px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60"
        >
          {loading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <ShieldCheck className="h-4 w-4" />
          )}
          登录运营端
        </button>
        <Link
          to={ROUTES.CHAT}
          className="mt-4 block text-center text-xs text-[var(--chat-text-soft)] underline"
        >
          返回用户端
        </Link>
      </div>
    </div>
  );
});
AdminLogin.displayName = 'AdminLogin';

/** 会员套餐管理 */
const SkuPanel = memo(() => {
  const [rows, setRows] = useState<AdminSku[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingCode, setSavingCode] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await adminApi.listSkus());
    } catch (err) {
      console.error('加载 SKU 失败', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patchRow = (code: string, patch: Partial<AdminSku>) => {
    setRows((prev) => prev.map((row) => (row.code === code ? { ...row, ...patch } : row)));
  };

  const save = async (row: AdminSku) => {
    setSavingCode(row.code);
    try {
      await adminApi.updateSku(row.code, {
        name: row.name,
        price: row.price,
        periodQuota: row.periodQuota,
        topupQuota: row.topupQuota,
        memberDays: row.memberDays,
        status: row.status,
      });
      message.success(`已保存 ${row.code}`);
    } catch (err) {
      console.error('保存 SKU 失败', err);
    } finally {
      setSavingCode('');
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-[var(--chat-text-soft)]" />
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-2xl border border-[var(--chat-border)]">
      <table className="w-full min-w-[760px] text-sm">
        <thead className="bg-[var(--chat-surface-soft)]">
          <tr>
            <th className={thClass}>Code</th>
            <th className={thClass}>名称</th>
            <th className={thClass}>价格(¥)</th>
            <th className={thClass}>周期配额</th>
            <th className={thClass}>加油包额度</th>
            <th className={thClass}>有效期(天)</th>
            <th className={thClass}>上架</th>
            <th className={thClass}>操作</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.code} className="border-t border-[var(--chat-border)]/60">
              <td className={`${tdClass} font-medium`}>{row.code}</td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  value={row.name}
                  onChange={(e) => patchRow(row.code, { name: e.target.value })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.price}
                  onChange={(e) => patchRow(row.code, { price: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.periodQuota ?? 0}
                  onChange={(e) => patchRow(row.code, { periodQuota: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.topupQuota ?? 0}
                  onChange={(e) => patchRow(row.code, { topupQuota: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.memberDays ?? 0}
                  onChange={(e) => patchRow(row.code, { memberDays: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  type="checkbox"
                  checked={row.status === 1}
                  onChange={(e) => patchRow(row.code, { status: e.target.checked ? 1 : 0 })}
                />
              </td>
              <td className={tdClass}>
                <button
                  type="button"
                  onClick={() => void save(row)}
                  disabled={Boolean(savingCode)}
                  className="rounded-full bg-[var(--chat-text)] px-4 py-1.5 text-xs text-white disabled:opacity-60"
                >
                  {savingCode === row.code ? '保存中...' : '保存'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
});
SkuPanel.displayName = 'SkuPanel';

/** 拼团活动管理 */
const GroupBuyPanel = memo(() => {
  const [rows, setRows] = useState<AdminGroupActivity[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await adminApi.listGroupActivities());
    } catch (err) {
      console.error('加载拼团活动失败', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patchRow = (activityId: number, patch: Partial<AdminGroupActivity>) => {
    setRows((prev) =>
      prev.map((row) => (row.activityId === activityId ? { ...row, ...patch } : row)),
    );
  };

  const save = async (row: AdminGroupActivity) => {
    setSavingId(row.activityId);
    try {
      await adminApi.updateGroupActivity(row.activityId, {
        activityName: row.activityName,
        takeLimitCount: row.takeLimitCount,
        target: row.target,
        validTime: row.validTime,
        status: row.status,
        marketExpr: row.marketExpr,
        goodsName: row.goodsName,
        originalPrice: row.originalPrice,
      });
      message.success(`活动 ${row.activityId} 已保存`);
      await load();
    } catch (err) {
      console.error('保存拼团活动失败', err);
    } finally {
      setSavingId(null);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-[var(--chat-text-soft)]" />
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-2xl border border-[var(--chat-border)]">
      <table className="w-full min-w-[980px] text-sm">
        <thead className="bg-[var(--chat-surface-soft)]">
          <tr>
            <th className={thClass}>活动ID</th>
            <th className={thClass}>活动名称</th>
            <th className={thClass}>商品</th>
            <th className={thClass}>原价(¥)</th>
            <th className={thClass}>直减(¥)</th>
            <th className={thClass}>拼团价(¥)</th>
            <th className={thClass}>成团人数</th>
            <th className={thClass}>时长(分)</th>
            <th className={thClass}>限购次数</th>
            <th className={thClass}>生效</th>
            <th className={thClass}>操作</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.activityId} className="border-t border-[var(--chat-border)]/60">
              <td className={`${tdClass} font-medium`}>{row.activityId}</td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  value={row.activityName ?? ''}
                  onChange={(e) => patchRow(row.activityId, { activityName: e.target.value })}
                />
              </td>
              <td className={`${tdClass} text-xs text-[var(--chat-text-soft)]`}>
                {row.goodsName || row.goodsId || '-'}
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.originalPrice ?? 0}
                  onChange={(e) =>
                    patchRow(row.activityId, { originalPrice: Number(e.target.value) })
                  }
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  value={row.marketExpr ?? ''}
                  onChange={(e) => patchRow(row.activityId, { marketExpr: e.target.value })}
                />
              </td>
              <td className={`${tdClass} font-medium text-emerald-700`}>
                {row.groupPayPrice != null ? `¥${row.groupPayPrice}` : '-'}
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.target ?? 1}
                  onChange={(e) => patchRow(row.activityId, { target: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.validTime ?? 15}
                  onChange={(e) => patchRow(row.activityId, { validTime: Number(e.target.value) })}
                />
              </td>
              <td className={tdClass}>
                <input
                  className={inputClass}
                  type="number"
                  value={row.takeLimitCount ?? 1}
                  onChange={(e) =>
                    patchRow(row.activityId, { takeLimitCount: Number(e.target.value) })
                  }
                />
              </td>
              <td className={tdClass}>
                <input
                  type="checkbox"
                  checked={row.status === 1}
                  onChange={(e) => patchRow(row.activityId, { status: e.target.checked ? 1 : 2 })}
                />
              </td>
              <td className={tdClass}>
                <button
                  type="button"
                  onClick={() => void save(row)}
                  disabled={savingId != null}
                  className="rounded-full bg-[var(--chat-text)] px-4 py-1.5 text-xs text-white disabled:opacity-60"
                >
                  {savingId === row.activityId ? '保存中...' : '保存'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
});
GroupBuyPanel.displayName = 'GroupBuyPanel';

/** 模型 Key 管理 */
const ModelPanel = memo(() => {
  const [apis, setApis] = useState<AdminClientApi[]>([]);
  const [models, setModels] = useState<AdminClientModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingApiId, setSavingApiId] = useState('');
  /** apiId -> 用户输入的新 Key（不回显旧 Key，读接口已脱敏） */
  const [newKeys, setNewKeys] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [apiList, modelList] = await Promise.all([
        adminApi.listClientApis(),
        adminApi.listClientModels(),
      ]);
      setApis(apiList);
      setModels(modelList);
    } catch (err) {
      console.error('加载模型配置失败', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patchApi = (apiId: string, patch: Partial<AdminClientApi>) => {
    setApis((prev) => prev.map((row) => (row.apiId === apiId ? { ...row, ...patch } : row)));
  };

  const save = async (row: AdminClientApi) => {
    setSavingApiId(row.apiId);
    try {
      const body: Partial<AdminClientApi> = {
        apiId: row.apiId,
        baseUrl: row.baseUrl,
        completionsPath: row.completionsPath,
        embeddingsPath: row.embeddingsPath,
        status: row.status,
      };
      const nextKey = newKeys[row.apiId]?.trim();
      // 读接口回显的是脱敏 Key（sk-a****），只有用户显式输入新 Key 才更新，避免把掩码写回库
      if (nextKey) {
        body.apiKey = nextKey;
      }
      await adminApi.updateClientApi(body);
      message.success(`模型 API ${row.apiId} 已保存${nextKey ? '（Key 已更新）' : ''}`);
      setNewKeys((prev) => ({ ...prev, [row.apiId]: '' }));
      await load();
    } catch (err) {
      console.error('保存模型 API 失败', err);
    } finally {
      setSavingApiId('');
    }
  };

  const modelsByApi = useMemo(() => {
    const map = new Map<string, AdminClientModel[]>();
    for (const model of models) {
      const key = model.apiId || '-';
      map.set(key, [...(map.get(key) || []), model]);
    }
    return map;
  }, [models]);

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-[var(--chat-text-soft)]" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {apis.map((row) => (
        <div
          key={row.apiId}
          className="rounded-2xl border border-[var(--chat-border)] bg-white/70 p-5 dark:bg-white/5"
        >
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <KeyRound className="h-4 w-4 text-[var(--chat-text-soft)]" />
              <span className="font-medium">{row.apiId}</span>
              <span className="rounded-full bg-[var(--chat-surface-soft)] px-2 py-0.5 text-xs text-[var(--chat-text-soft)]">
                当前 Key：{row.apiKey || '-'}
              </span>
            </div>
            <label className="flex items-center gap-1 text-xs text-[var(--chat-text-soft)]">
              <input
                type="checkbox"
                checked={row.status === 1}
                onChange={(e) => patchApi(row.apiId, { status: e.target.checked ? 1 : 0 })}
              />
              启用
            </label>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs text-[var(--chat-text-soft)]">Base URL</label>
              <input
                className={inputClass}
                value={row.baseUrl}
                onChange={(e) => patchApi(row.apiId, { baseUrl: e.target.value })}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs text-[var(--chat-text-soft)]">
                新 API Key（留空则不修改）
              </label>
              <input
                className={inputClass}
                type="password"
                placeholder="sk-..."
                value={newKeys[row.apiId] ?? ''}
                onChange={(e) => setNewKeys((prev) => ({ ...prev, [row.apiId]: e.target.value }))}
              />
            </div>
          </div>
          {(modelsByApi.get(row.apiId) || []).length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-2 text-xs text-[var(--chat-text-soft)]">
              挂载模型：
              {(modelsByApi.get(row.apiId) || []).map((model) => (
                <span
                  key={model.modelId}
                  className="rounded-full bg-[var(--chat-surface-soft)] px-2 py-0.5"
                >
                  {model.modelName}
                </span>
              ))}
            </div>
          ) : null}
          <div className="mt-4">
            <button
              type="button"
              onClick={() => void save(row)}
              disabled={Boolean(savingApiId)}
              className="rounded-full bg-[var(--chat-text)] px-4 py-1.5 text-xs text-white disabled:opacity-60"
            >
              {savingApiId === row.apiId ? '保存中...' : '保存'}
            </button>
          </div>
        </div>
      ))}
      {apis.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-10 text-center text-sm text-[var(--chat-text-soft)]">
          暂无模型 API 配置
        </div>
      ) : null}
    </div>
  );
});
ModelPanel.displayName = 'ModelPanel';

const TABS: Array<{ key: AdminTab; label: string; icon: typeof Package }> = [
  { key: 'skus', label: '会员套餐', icon: Package },
  { key: 'groupbuy', label: '拼团活动', icon: Users },
  { key: 'models', label: '模型 Key', icon: KeyRound },
];

const AdminPage = memo(() => {
  const [authed, setAuthed] = useState(() => isAuthenticated() && getStoredRole() === 'ADMIN');
  const [tab, setTab] = useState<AdminTab>('skus');
  const [reloadKey, setReloadKey] = useState(0);

  const logout = useCallback(() => {
    void authApi.logout();
    clearAuthTokens();
    localStorage.removeItem(ADMIN_ROLE_KEY);
    setAuthed(false);
  }, []);

  if (!authed) {
    return <AdminLogin onLogin={() => setAuthed(true)} />;
  }

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <header className="border-b border-[var(--chat-border)] bg-[var(--chat-surface)]/90 px-4 py-3 backdrop-blur-md sm:px-6">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5" />
            <span className="text-sm font-semibold">AI Group 运营端</span>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setReloadKey((prev) => prev + 1)}
              className="inline-flex items-center gap-1.5 rounded-full border border-[var(--chat-border)] px-3 py-1.5 text-xs text-[var(--chat-text-soft)]"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              刷新
            </button>
            <Link
              to={ROUTES.CHAT}
              className="rounded-full border border-[var(--chat-border)] px-3 py-1.5 text-xs text-[var(--chat-text-soft)]"
            >
              返回用户端
            </Link>
            <button
              type="button"
              onClick={logout}
              className="inline-flex items-center gap-1.5 rounded-full border border-[var(--chat-border)] px-3 py-1.5 text-xs text-[var(--chat-text-soft)]"
            >
              <LogOut className="h-3.5 w-3.5" />
              退出
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="mb-5 inline-flex rounded-full border border-[var(--chat-border)] bg-[var(--chat-surface)]/70 p-1">
          {TABS.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              type="button"
              onClick={() => setTab(key)}
              className={
                tab === key
                  ? 'inline-flex items-center gap-1.5 rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white'
                  : 'inline-flex items-center gap-1.5 rounded-full px-4 py-2 text-sm text-[var(--chat-text-soft)]'
              }
            >
              <Icon className="h-4 w-4" />
              {label}
            </button>
          ))}
        </div>

        <div key={`${tab}-${reloadKey}`}>
          {tab === 'skus' ? <SkuPanel /> : null}
          {tab === 'groupbuy' ? <GroupBuyPanel /> : null}
          {tab === 'models' ? <ModelPanel /> : null}
        </div>

        <p className="mt-6 text-xs text-[var(--chat-text-soft)]">
          提示：改价/改活动保存后立即生效（拼团活动缓存已同步逐出）；模型 Key
          读取时脱敏展示，仅在输入新 Key 时更新。
        </p>
      </main>
    </div>
  );
});
AdminPage.displayName = 'AdminPage';

export default AdminPage;

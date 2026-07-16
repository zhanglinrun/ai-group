import { Fragment, memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { message } from 'antd';
import {
  KeyRound,
  Loader2,
  LogOut,
  Package,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Trash2,
  Users,
  X,
} from 'lucide-react';
import { authApi } from '@/services/auth';
import {
  adminApi,
  type AdminClientApi,
  type AdminClientModel,
  type AdminGroupActivity,
  type AdminGroupActivityCreate,
  type AdminGroupTier,
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

/** 永久额度包管理 */
const SkuPanel = memo(() => {
  const [rows, setRows] = useState<AdminSku[]>([]);
  const [activities, setActivities] = useState<AdminGroupActivity[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingCode, setSavingCode] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [draft, setDraft] = useState<Partial<AdminSku>>({
    code: '',
    name: '',
    price: 0,
    baseQuota: 60,
    status: 1,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [skuRows, activityRows] = await Promise.all([
        adminApi.listSkus(),
        adminApi.listGroupActivities(),
      ]);
      setRows(skuRows);
      setActivities(activityRows);
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
        baseQuota: row.baseQuota,
        status: row.status,
        groupGoodsId: row.groupGoodsId,
        groupActivityId: row.groupActivityId,
      });
      message.success(`已保存 ${row.code}`);
    } catch (err) {
      console.error('保存 SKU 失败', err);
    } finally {
      setSavingCode('');
    }
  };

  const create = async () => {
    if (!draft.code?.trim() || !draft.name?.trim()) {
      message.warning('请填写套餐 Code 和名称');
      return;
    }
    setSavingCode('__new__');
    try {
      await adminApi.createSku(draft);
      message.success(`套餐 ${draft.name} 已创建，启用后用户端立即可见`);
      setShowCreate(false);
      setDraft({
        code: '',
        name: '',
        price: 0,
        baseQuota: 60,
        status: 1,
      });
      await load();
    } catch (err) {
      console.error('创建套餐失败', err);
    } finally {
      setSavingCode('');
    }
  };

  const deleteSku = async (row: AdminSku) => {
    if (!window.confirm(`确定删除套餐 ${row.name}？`)) return;
    setSavingCode(row.code);
    try {
      await adminApi.deleteSku(row.code);
      await load();
    } finally {
      setSavingCode('');
    }
  };

  const selectActivity = (sku: Partial<AdminSku>, value: string) => {
    const activity = activities.find((item) => String(item.activityId) === value);
    return {
      ...sku,
      groupActivityId: activity?.activityId ?? null,
      groupGoodsId: activity?.goodsId ?? null,
    };
  };

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-[var(--chat-text-soft)]" />
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => setShowCreate((value) => !value)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
        >
          {showCreate ? <X className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />}
          {showCreate ? '取消' : '新增套餐'}
        </button>
      </div>
      {showCreate ? (
        <div className="rounded-lg border border-dashed border-[var(--chat-border)] p-4">
          <div className="mb-3 text-sm font-medium">新建额度包</div>
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-xs text-[var(--chat-text-soft)]">
              Code
              <input
                className={`${inputClass} mt-1`}
                value={draft.code || ''}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, code: e.target.value.toUpperCase() }))
                }
                placeholder="QUOTA_LIGHT"
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              用户端名称
              <input
                className={`${inputClass} mt-1`}
                value={draft.name || ''}
                onChange={(e) => setDraft((prev) => ({ ...prev, name: e.target.value }))}
                placeholder="轻享额度包"
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              原价
              <input
                className={`${inputClass} mt-1`}
                type="number"
                value={draft.price || 0}
                onChange={(e) => setDraft((prev) => ({ ...prev, price: Number(e.target.value) }))}
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              基础额度（点）
              <input
                className={`${inputClass} mt-1`}
                type="number"
                value={draft.baseQuota || 0}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, baseQuota: Number(e.target.value) }))
                }
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)] md:col-span-2">
              关联拼团活动
              <select
                className={`${inputClass} mt-1`}
                value={draft.groupActivityId || ''}
                onChange={(e) => setDraft((prev) => selectActivity(prev, e.target.value))}
              >
                <option value="">不支持拼团</option>
                {activities.map((activity) => (
                  <option key={activity.activityId} value={activity.activityId}>
                    {activity.activityName} · {activity.goodsName}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <button
            type="button"
            onClick={() => void create()}
            disabled={Boolean(savingCode)}
            className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white disabled:opacity-60"
          >
            <Save className="h-3.5 w-3.5" />
            创建套餐
          </button>
        </div>
      ) : null}
      <div className="overflow-x-auto rounded-2xl border border-[var(--chat-border)]">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-[var(--chat-surface-soft)]">
            <tr>
              <th className={thClass}>Code</th>
              <th className={thClass}>名称</th>
              <th className={thClass}>价格(¥)</th>
              <th className={thClass}>基础额度(点)</th>
              <th className={thClass}>关联拼团活动</th>
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
                    value={row.baseQuota ?? 0}
                    onChange={(e) => patchRow(row.code, { baseQuota: Number(e.target.value) })}
                  />
                </td>
                <td className={tdClass}>
                  <select
                    className={inputClass}
                    value={row.groupActivityId ?? ''}
                    onChange={(e) => {
                      const next = selectActivity(row, e.target.value);
                      patchRow(row.code, {
                        groupActivityId: next.groupActivityId,
                        groupGoodsId: next.groupGoodsId,
                      });
                    }}
                  >
                    <option value="">不支持拼团</option>
                    {activities.map((activity) => (
                      <option key={activity.activityId} value={activity.activityId}>
                        {activity.activityName}
                      </option>
                    ))}
                  </select>
                </td>
                <td className={tdClass}>
                  <input
                    type="checkbox"
                    checked={row.status === 1}
                    onChange={(e) => patchRow(row.code, { status: e.target.checked ? 1 : 0 })}
                  />
                </td>
                <td className={tdClass}>
                  <div className="flex gap-1">
                    <button
                      type="button"
                      onClick={() => void save(row)}
                      disabled={Boolean(savingCode)}
                      className="rounded-full bg-[var(--chat-text)] px-4 py-1.5 text-xs text-white disabled:opacity-60"
                    >
                      {savingCode === row.code ? '保存中...' : '保存'}
                    </button>
                    {row.code !== 'FREE' ? (
                      <button
                        type="button"
                        title="删除套餐"
                        onClick={() => void deleteSku(row)}
                        className="rounded-md border border-red-200 p-2 text-red-600"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    ) : null}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
});
SkuPanel.displayName = 'SkuPanel';

/** 拼团活动管理 */
const GroupBuyPanel = memo(() => {
  const [rows, setRows] = useState<AdminGroupActivity[]>([]);
  const [skus, setSkus] = useState<AdminSku[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<number | null>(null);
  const [savingTiersId, setSavingTiersId] = useState<number | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [draft, setDraft] = useState<AdminGroupActivityCreate>({
    activityId: 0,
    activityName: '',
    goodsId: '',
    goodsName: '',
    discountId: '',
    originalPrice: 0,
    marketExpr: '0',
    marketPlan: 'ZJ',
    activityType: 1,
    target: 10,
    validTime: 1440,
    takeLimitCount: 10,
    status: 1,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [activityRows, skuRows] = await Promise.all([
        adminApi.listGroupActivities(),
        adminApi.listSkus(),
      ]);
      setRows(activityRows);
      setSkus(skuRows);
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
        marketPlan: row.marketPlan as 'ZJ' | 'MJ' | 'ZK' | 'N',
        goodsName: row.goodsName,
        originalPrice: row.originalPrice,
        activityType: row.activityType,
      });
      message.success(`活动 ${row.activityId} 已保存`);
      await load();
    } catch (err) {
      console.error('保存拼团活动失败', err);
    } finally {
      setSavingId(null);
    }
  };

  const createActivity = async () => {
    if (
      !draft.activityId ||
      !draft.activityName.trim() ||
      !draft.goodsId.trim() ||
      !draft.discountId.trim()
    ) {
      message.warning('请填写活动 ID、名称、商品 ID 和折扣 ID');
      return;
    }
    setSavingId(-1);
    try {
      await adminApi.createGroupActivity(draft);
      message.success(`活动 ${draft.activityName} 已创建`);
      setShowCreate(false);
      await load();
    } catch (err) {
      console.error('创建拼团活动失败', err);
    } finally {
      setSavingId(null);
    }
  };

  const patchTier = (activityId: number, tierNo: number, patch: Partial<AdminGroupTier>) => {
    setRows((prev) =>
      prev.map((row) =>
        row.activityId === activityId
          ? {
              ...row,
              tiers: (row.tiers || []).map((tier) =>
                tier.tierNo === tierNo ? { ...tier, ...patch } : tier,
              ),
            }
          : row,
      ),
    );
  };

  const addTier = (activityId: number) => {
    setRows((prev) =>
      prev.map((row) => {
        if (row.activityId !== activityId) return row;
        const tiers = row.tiers || [];
        const tierNo = Math.max(0, ...tiers.map((tier) => tier.tierNo)) + 1;
        return {
          ...row,
          activityType: 1,
          tiers: [
            ...tiers,
            { tierNo, tierName: `${tierNo}档`, targetCount: tierNo, bonusQuota: 0, status: 1 },
          ],
        };
      }),
    );
  };

  const removeTier = (activityId: number, tierNo: number) => {
    setRows((prev) =>
      prev.map((row) =>
        row.activityId === activityId
          ? { ...row, tiers: (row.tiers || []).filter((tier) => tier.tierNo !== tierNo) }
          : row,
      ),
    );
  };

  const saveTiers = async (row: AdminGroupActivity) => {
    setSavingTiersId(row.activityId);
    try {
      await adminApi.replaceGroupActivityTiers(row.activityId, row.tiers || []);
      await adminApi.updateGroupActivity(row.activityId, {
        activityType: (row.tiers || []).length ? 1 : 0,
        target: row.target,
      });
      message.success(`活动 ${row.activityName} 的阶梯档位已保存`);
      await load();
    } finally {
      setSavingTiersId(null);
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
    <div className="space-y-3">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => setShowCreate((value) => !value)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
        >
          {showCreate ? <X className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />}
          {showCreate ? '取消' : '新增活动'}
        </button>
      </div>
      {showCreate ? (
        <div className="rounded-lg border border-dashed border-[var(--chat-border)] p-4">
          <div className="mb-3 text-sm font-medium">新建拼团活动</div>
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-xs text-[var(--chat-text-soft)]">
              活动 ID
              <input
                className={`${inputClass} mt-1`}
                type="number"
                value={draft.activityId || ''}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, activityId: Number(e.target.value) }))
                }
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              活动名称
              <input
                className={`${inputClass} mt-1`}
                value={draft.activityName}
                onChange={(e) => setDraft((prev) => ({ ...prev, activityName: e.target.value }))}
                placeholder="自定义额度包拼团"
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              商品 ID
              <input
                className={`${inputClass} mt-1`}
                value={draft.goodsId}
                onChange={(e) => setDraft((prev) => ({ ...prev, goodsId: e.target.value }))}
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              商品名称
              <input
                className={`${inputClass} mt-1`}
                value={draft.goodsName}
                onChange={(e) => setDraft((prev) => ({ ...prev, goodsName: e.target.value }))}
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              折扣 ID
              <input
                className={`${inputClass} mt-1`}
                value={draft.discountId}
                maxLength={8}
                onChange={(e) => setDraft((prev) => ({ ...prev, discountId: e.target.value }))}
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              原价
              <input
                className={`${inputClass} mt-1`}
                type="number"
                value={draft.originalPrice}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, originalPrice: Number(e.target.value) }))
                }
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              优惠类型
              <select
                className={`${inputClass} mt-1`}
                value={draft.marketPlan}
                onChange={(e) =>
                  setDraft((prev) => ({
                    ...prev,
                    marketPlan: e.target.value as 'ZJ' | 'MJ' | 'ZK' | 'N',
                    marketExpr:
                      e.target.value === 'MJ' ? '100,10' : e.target.value === 'ZK' ? '0.8' : '0',
                  }))
                }
              >
                <option value="ZJ">直减</option>
                <option value="MJ">满减</option>
                <option value="ZK">折扣</option>
                <option value="N">固定价购</option>
              </select>
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              优惠表达式
              <input
                className={`${inputClass} mt-1`}
                value={draft.marketExpr}
                onChange={(e) => setDraft((prev) => ({ ...prev, marketExpr: e.target.value }))}
                placeholder={
                  draft.marketPlan === 'MJ'
                    ? '满额,减额，如 100,10'
                    : draft.marketPlan === 'ZK'
                      ? '倍率，如 0.8'
                      : '金额，如 20'
                }
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              团容量
              <input
                className={`${inputClass} mt-1`}
                type="number"
                value={draft.target}
                onChange={(e) => setDraft((prev) => ({ ...prev, target: Number(e.target.value) }))}
              />
            </label>
          </div>
          <button
            type="button"
            onClick={() => void createActivity()}
            disabled={savingId != null}
            className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
          >
            <Save className="h-3.5 w-3.5" />
            创建活动
          </button>
        </div>
      ) : null}
      <div className="overflow-x-auto rounded-2xl border border-[var(--chat-border)]">
        <table className="w-full min-w-[980px] text-sm">
          <thead className="bg-[var(--chat-surface-soft)]">
            <tr>
              <th className={thClass}>活动ID</th>
              <th className={thClass}>活动名称</th>
              <th className={thClass}>商品</th>
              <th className={thClass}>原价(¥)</th>
              <th className={thClass}>优惠类型</th>
              <th className={thClass}>优惠表达式</th>
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
              <Fragment key={row.activityId}>
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
                    <div>{row.goodsName || row.goodsId || '-'}</div>
                    <div className="mt-1 font-mono text-[10px]">{row.goodsId}</div>
                    <div className="mt-1 text-[10px] text-emerald-700">
                      {skus.find((sku) => sku.groupActivityId === row.activityId)?.name ||
                        '未关联套餐'}
                    </div>
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
                    <select
                      className={inputClass}
                      value={row.marketPlan || 'ZJ'}
                      onChange={(e) =>
                        patchRow(row.activityId, {
                          marketPlan: e.target.value as 'ZJ' | 'MJ' | 'ZK' | 'N',
                        })
                      }
                    >
                      <option value="ZJ">直减</option>
                      <option value="MJ">满减</option>
                      <option value="ZK">折扣</option>
                      <option value="N">固定价购</option>
                    </select>
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
                      onChange={(e) =>
                        patchRow(row.activityId, { validTime: Number(e.target.value) })
                      }
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
                      onChange={(e) =>
                        patchRow(row.activityId, { status: e.target.checked ? 1 : 2 })
                      }
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
                <tr className="border-t border-[var(--chat-border)]/60 bg-[var(--chat-surface-soft)]/35">
                  <td colSpan={12} className="px-4 py-3">
                    <div className="mb-2 flex items-center justify-between">
                      <div>
                        <span className="text-xs font-medium">阶梯档位</span>
                        <span className="ml-2 text-[11px] text-[var(--chat-text-soft)]">
                          拼团价固定为 ¥{row.groupPayPrice ?? '-'}；人数档位改变累计加赠额度
                        </span>
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => addTier(row.activityId)}
                          className="inline-flex items-center gap-1 rounded-md border border-[var(--chat-border)] px-2 py-1 text-xs"
                        >
                          <Plus className="h-3 w-3" />
                          添加档位
                        </button>
                        <button
                          type="button"
                          onClick={() => void saveTiers(row)}
                          disabled={savingTiersId != null}
                          className="inline-flex items-center gap-1 rounded-md bg-[var(--chat-text)] px-2 py-1 text-xs text-white disabled:opacity-60"
                        >
                          <Save className="h-3 w-3" />
                          保存档位
                        </button>
                      </div>
                    </div>
                    {(row.tiers || []).length ? (
                      <div className="grid gap-2 md:grid-cols-3">
                        {(row.tiers || []).map((tier) => (
                          <div
                            key={tier.tierNo}
                            className="grid grid-cols-[1fr_80px_100px_32px] items-end gap-2 rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] p-2"
                          >
                            <label className="text-[10px] text-[var(--chat-text-soft)]">
                              档位名称
                              <input
                                className={`${inputClass} mt-1`}
                                value={tier.tierName}
                                onChange={(e) =>
                                  patchTier(row.activityId, tier.tierNo, {
                                    tierName: e.target.value,
                                  })
                                }
                              />
                            </label>
                            <label className="text-[10px] text-[var(--chat-text-soft)]">
                              人数
                              <input
                                className={`${inputClass} mt-1`}
                                type="number"
                                value={tier.targetCount}
                                onChange={(e) =>
                                  patchTier(row.activityId, tier.tierNo, {
                                    targetCount: Number(e.target.value),
                                  })
                                }
                              />
                            </label>
                            <label className="text-[10px] text-[var(--chat-text-soft)]">
                              加赠额度
                              <input
                                className={`${inputClass} mt-1`}
                                type="number"
                                value={tier.bonusQuota}
                                onChange={(e) =>
                                  patchTier(row.activityId, tier.tierNo, {
                                    bonusQuota: Number(e.target.value),
                                  })
                                }
                              />
                            </label>
                            <button
                              type="button"
                              title="移除档位"
                              onClick={() => removeTier(row.activityId, tier.tierNo)}
                              className="mb-0.5 rounded-md border border-red-200 p-2 text-red-600"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </button>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="py-2 text-xs text-[var(--chat-text-soft)]">
                        经典拼团，暂无人数阶梯
                      </div>
                    )}
                  </td>
                </tr>
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
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
  const [savingModelId, setSavingModelId] = useState('');
  const [showNewApi, setShowNewApi] = useState(false);
  const [newApi, setNewApi] = useState<Partial<AdminClientApi>>({
    apiId: '',
    baseUrl: '',
    apiKey: '',
    completionsPath: '/chat/completions',
    embeddingsPath: '/embeddings',
    status: 1,
  });
  const [newModelApiId, setNewModelApiId] = useState('');
  const [newModel, setNewModel] = useState<Partial<AdminClientModel>>({
    modelId: '',
    modelName: '',
    modelType: 'openai',
    modelUsage: 'chat',
    inputCreditsPerMillion: 5,
    outputCreditsPerMillion: 30,
    status: 1,
  });
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

  const patchModel = (modelId: string, patch: Partial<AdminClientModel>) => {
    setModels((prev) =>
      prev.map((model) => (model.modelId === modelId ? { ...model, ...patch } : model)),
    );
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

  const createApi = async () => {
    if (!newApi.apiId?.trim() || !newApi.baseUrl?.trim() || !newApi.apiKey?.trim()) {
      message.warning('请填写 API ID、Base URL 和 API Key');
      return;
    }
    setSavingApiId('__new__');
    try {
      await adminApi.createClientApi({
        ...newApi,
        apiId: newApi.apiId.trim(),
        baseUrl: newApi.baseUrl.trim().replace(/\/$/, ''),
        apiKey: newApi.apiKey.trim(),
      });
      message.success(`模型 API ${newApi.apiId} 已创建`);
      setShowNewApi(false);
      setNewApi({
        apiId: '',
        baseUrl: '',
        apiKey: '',
        completionsPath: '/chat/completions',
        embeddingsPath: '/embeddings',
        status: 1,
      });
      await load();
    } catch (err) {
      console.error('创建模型 API 失败', err);
    } finally {
      setSavingApiId('');
    }
  };

  const deleteApi = async (apiId: string) => {
    if ((modelsByApi.get(apiId) || []).length > 0) {
      message.warning('请先删除该 API 下挂载的模型');
      return;
    }
    if (!window.confirm(`确定删除模型 API ${apiId}？`)) return;
    setSavingApiId(apiId);
    try {
      await adminApi.deleteClientApi(apiId);
      message.success(`模型 API ${apiId} 已删除`);
      await load();
    } catch (err) {
      console.error('删除模型 API 失败', err);
    } finally {
      setSavingApiId('');
    }
  };

  const openNewModel = (apiId: string) => {
    setNewModelApiId(apiId);
    setNewModel({
      modelId: '',
      modelName: '',
      modelType: 'openai',
      modelUsage: 'chat',
      inputCreditsPerMillion: 5,
      outputCreditsPerMillion: 30,
      status: 1,
    });
  };

  const createModel = async (apiId: string) => {
    if (!newModel.modelId?.trim() || !newModel.modelName?.trim()) {
      message.warning('请填写模型 ID 和模型名称');
      return;
    }
    setSavingModelId('__new__');
    try {
      await adminApi.createClientModel({
        ...newModel,
        apiId,
        modelId: newModel.modelId.trim(),
        modelName: newModel.modelName.trim(),
      });
      message.success(`模型 ${newModel.modelName} 已添加`);
      setNewModelApiId('');
      await load();
    } catch (err) {
      console.error('创建模型失败', err);
    } finally {
      setSavingModelId('');
    }
  };

  const saveModel = async (model: AdminClientModel) => {
    setSavingModelId(model.modelId);
    try {
      await adminApi.updateClientModel(model);
      message.success(`模型 ${model.modelName} 已保存`);
      await load();
    } catch (err) {
      console.error('保存模型失败', err);
    } finally {
      setSavingModelId('');
    }
  };

  const deleteModel = async (model: AdminClientModel) => {
    if (!window.confirm(`确定删除模型 ${model.modelName}？`)) return;
    setSavingModelId(model.modelId);
    try {
      await adminApi.deleteClientModel(model.modelId);
      message.success(`模型 ${model.modelName} 已删除`);
      await load();
    } catch (err) {
      console.error('删除模型失败', err);
    } finally {
      setSavingModelId('');
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
      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => setShowNewApi((value) => !value)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white"
        >
          {showNewApi ? <X className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />}
          {showNewApi ? '取消' : '新增 API'}
        </button>
      </div>
      {showNewApi ? (
        <div className="rounded-lg border border-dashed border-[var(--chat-border)] bg-[var(--chat-surface)]/70 p-4">
          <div className="mb-3 text-sm font-medium">新建模型 API</div>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="text-xs text-[var(--chat-text-soft)]">
              API ID
              <input
                className={`${inputClass} mt-1`}
                value={newApi.apiId || ''}
                onChange={(e) => setNewApi((prev) => ({ ...prev, apiId: e.target.value }))}
                placeholder="dashscope_api"
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              Base URL
              <input
                className={`${inputClass} mt-1`}
                value={newApi.baseUrl || ''}
                onChange={(e) => setNewApi((prev) => ({ ...prev, baseUrl: e.target.value }))}
                placeholder="https://example.com/v1"
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              API Key
              <input
                className={`${inputClass} mt-1`}
                type="password"
                value={newApi.apiKey || ''}
                onChange={(e) => setNewApi((prev) => ({ ...prev, apiKey: e.target.value }))}
                placeholder="sk-..."
              />
            </label>
            <label className="text-xs text-[var(--chat-text-soft)]">
              对话补全路径
              <input
                className={`${inputClass} mt-1`}
                value={newApi.completionsPath || ''}
                onChange={(e) =>
                  setNewApi((prev) => ({ ...prev, completionsPath: e.target.value }))
                }
              />
            </label>
          </div>
          <button
            type="button"
            onClick={() => void createApi()}
            disabled={Boolean(savingApiId)}
            className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white disabled:opacity-60"
          >
            {savingApiId === '__new__' ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Save className="h-3.5 w-3.5" />
            )}
            创建 API
          </button>
        </div>
      ) : null}
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
          <div className="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => void save(row)}
              disabled={Boolean(savingApiId)}
              className="inline-flex items-center gap-1.5 rounded-lg bg-[var(--chat-text)] px-3 py-2 text-xs text-white disabled:opacity-60"
            >
              <Save className="h-3.5 w-3.5" />
              {savingApiId === row.apiId ? '保存中...' : '保存'}
            </button>
            <button
              type="button"
              onClick={() => openNewModel(row.apiId)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-[var(--chat-border)] px-3 py-2 text-xs"
            >
              <Plus className="h-3.5 w-3.5" />
              添加模型
            </button>
            <button
              type="button"
              onClick={() => void deleteApi(row.apiId)}
              disabled={Boolean(savingApiId)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 px-3 py-2 text-xs text-red-600 disabled:opacity-60"
            >
              <Trash2 className="h-3.5 w-3.5" />
              删除 API
            </button>
          </div>
          <div className="mt-4 overflow-x-auto border-t border-[var(--chat-border)] pt-4">
            <table className="w-full min-w-[720px] text-xs">
              <thead>
                <tr>
                  <th className={thClass}>模型 ID</th>
                  <th className={thClass}>模型名称</th>
                  <th className={thClass}>协议/厂商</th>
                  <th className={thClass}>用途</th>
                  <th className={thClass}>输入费率</th>
                  <th className={thClass}>输出费率</th>
                  <th className={thClass}>启用</th>
                  <th className={thClass}>操作</th>
                </tr>
              </thead>
              <tbody>
                {(modelsByApi.get(row.apiId) || []).map((model) => (
                  <tr key={model.modelId} className="border-t border-[var(--chat-border)]/60">
                    <td className={`${tdClass} font-mono`}>{model.modelId}</td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={model.modelName}
                        onChange={(e) => patchModel(model.modelId, { modelName: e.target.value })}
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={model.modelType || ''}
                        onChange={(e) => patchModel(model.modelId, { modelType: e.target.value })}
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={model.modelUsage || 'chat'}
                        onChange={(e) => patchModel(model.modelId, { modelUsage: e.target.value })}
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        type="number"
                        min={0}
                        value={model.inputCreditsPerMillion ?? 5}
                        onChange={(e) =>
                          patchModel(model.modelId, {
                            inputCreditsPerMillion: Number(e.target.value),
                          })
                        }
                        title="每百万输入 Token 消耗的额度点"
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        type="number"
                        min={0}
                        value={model.outputCreditsPerMillion ?? 30}
                        onChange={(e) =>
                          patchModel(model.modelId, {
                            outputCreditsPerMillion: Number(e.target.value),
                          })
                        }
                        title="每百万输出 Token 消耗的额度点"
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        type="checkbox"
                        checked={model.status === 1}
                        onChange={(e) =>
                          patchModel(model.modelId, { status: e.target.checked ? 1 : 0 })
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <div className="flex gap-1">
                        <button
                          type="button"
                          title="保存模型"
                          onClick={() => void saveModel(model)}
                          disabled={Boolean(savingModelId)}
                          className="rounded-md border border-[var(--chat-border)] p-2 disabled:opacity-60"
                        >
                          <Save className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          title="删除模型"
                          onClick={() => void deleteModel(model)}
                          disabled={Boolean(savingModelId)}
                          className="rounded-md border border-red-200 p-2 text-red-600 disabled:opacity-60"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {newModelApiId === row.apiId ? (
                  <tr className="border-t border-[var(--chat-border)]/60 bg-[var(--chat-surface-soft)]/50">
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={newModel.modelId || ''}
                        onChange={(e) =>
                          setNewModel((prev) => ({ ...prev, modelId: e.target.value }))
                        }
                        placeholder="model_002"
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={newModel.modelName || ''}
                        onChange={(e) =>
                          setNewModel((prev) => ({ ...prev, modelName: e.target.value }))
                        }
                        placeholder="qwen-max"
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={newModel.modelType || ''}
                        onChange={(e) =>
                          setNewModel((prev) => ({ ...prev, modelType: e.target.value }))
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        value={newModel.modelUsage || ''}
                        onChange={(e) =>
                          setNewModel((prev) => ({ ...prev, modelUsage: e.target.value }))
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        type="number"
                        min={0}
                        value={newModel.inputCreditsPerMillion ?? 5}
                        onChange={(e) =>
                          setNewModel((prev) => ({
                            ...prev,
                            inputCreditsPerMillion: Number(e.target.value),
                          }))
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        className={inputClass}
                        type="number"
                        min={0}
                        value={newModel.outputCreditsPerMillion ?? 30}
                        onChange={(e) =>
                          setNewModel((prev) => ({
                            ...prev,
                            outputCreditsPerMillion: Number(e.target.value),
                          }))
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <input
                        type="checkbox"
                        checked={newModel.status === 1}
                        onChange={(e) =>
                          setNewModel((prev) => ({ ...prev, status: e.target.checked ? 1 : 0 }))
                        }
                      />
                    </td>
                    <td className={tdClass}>
                      <div className="flex gap-1">
                        <button
                          type="button"
                          title="创建模型"
                          onClick={() => void createModel(row.apiId)}
                          disabled={Boolean(savingModelId)}
                          className="rounded-md bg-[var(--chat-text)] p-2 text-white disabled:opacity-60"
                        >
                          <Save className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          title="取消"
                          onClick={() => setNewModelApiId('')}
                          className="rounded-md border border-[var(--chat-border)] p-2"
                        >
                          <X className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
            {(modelsByApi.get(row.apiId) || []).length === 0 && newModelApiId !== row.apiId ? (
              <div className="py-4 text-center text-xs text-[var(--chat-text-soft)]">
                暂无挂载模型
              </div>
            ) : null}
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
  { key: 'skus', label: '额度包', icon: Package },
  { key: 'groupbuy', label: '拼团活动', icon: Users },
  { key: 'models', label: '模型与费率', icon: KeyRound },
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

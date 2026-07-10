import { memo, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock3, Loader2, ShoppingBag, Users } from 'lucide-react';
import ShellNav from '@/components/ShellNav';
import MembershipStatusCard from '@/components/trade/MembershipStatusCard';
import QuotaOverview from '@/components/trade/QuotaOverview';
import { bffApi, type AccountSummary } from '@/services/bff';
import { ROUTES } from '@/router/routes';

const AccountPage = memo(() => {
  const [loading, setLoading] = useState(true);
  const [summary, setSummary] = useState<AccountSummary | null>(null);

  useEffect(() => {
    bffApi
      .getAccountSummary()
      .then((data) => setSummary(data))
      .catch((error) => console.error('加载会员信息失败', error))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h1 className="font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              会员中心
            </h1>
            <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
              查看会员状态、配额余额与拼团进度
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Link
              to={ROUTES.PRICING}
              className="inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] bg-white/70 px-4 py-2 text-sm font-medium shadow-[var(--shadow-sm)] backdrop-blur-sm"
            >
              <ShoppingBag className="h-4 w-4" />
              购买套餐
            </Link>
            <Link
              to={ROUTES.GROUP_BUY_HALL}
              className="inline-flex items-center gap-2 rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm font-medium text-white"
            >
              <Users className="h-4 w-4" />
              拼团大厅
            </Link>
          </div>
        </div>

        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
          </div>
        ) : !summary ? (
          <div className="rounded-3xl border border-dashed border-[var(--chat-border)] px-6 py-16 text-center text-sm text-[var(--chat-text-soft)]">
            暂无会员信息
          </div>
        ) : (
          <div className="space-y-6">
            <MembershipStatusCard summary={summary} />
            <QuotaOverview summary={summary} />

            {(summary.pendingGroupOrders?.length ?? 0) > 0 ? (
              <section className="rounded-3xl border border-amber-200/80 bg-amber-50/70 p-6 shadow-[var(--shadow-sm)]">
                <div className="mb-4 flex items-center gap-2 text-base font-medium text-amber-900">
                  <Clock3 className="h-4 w-4" />
                  待成团订单
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  {summary.pendingGroupOrders?.map((order) => (
                    <article
                      key={order.orderId}
                      className="rounded-2xl border border-amber-200/70 bg-white/80 px-4 py-4"
                    >
                      <div className="font-medium">{order.productName || '会员订单'}</div>
                      <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                        订单号 {order.orderId}
                      </div>
                      <div className="mt-3 inline-flex rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                        {order.status || '等待成团'}
                      </div>
                      {order.paidAt ? (
                        <div className="mt-2 text-xs text-[var(--chat-text-soft)]">
                          支付时间 {order.paidAt}
                        </div>
                      ) : null}
                    </article>
                  ))}
                </div>
                <p className="mt-4 text-sm text-amber-800/80">
                  拼团支付成功仅锁定名额，成团后才会发放 Pro 权益与配额。
                </p>
              </section>
            ) : null}

            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]">
              <div className="text-base font-medium">权益说明</div>
              <div className="mt-4 grid gap-3 text-sm text-[var(--chat-text-soft)] sm:grid-cols-3">
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">周期额度</div>
                  <div className="mt-2">会员周期内可使用的配额，到期后按规则重置或失效。</div>
                </div>
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">加油包额度</div>
                  <div className="mt-2">单独购买的额度包，通常可叠加使用，适合临时补充用量。</div>
                </div>
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">拼团购买</div>
                  <div className="mt-2">
                    参与拼团享优惠价格，成团后自动开通；也可选择直接购买立即生效。
                  </div>
                </div>
              </div>
            </section>
          </div>
        )}
      </main>
    </div>
  );
});

AccountPage.displayName = 'AccountPage';

export default AccountPage;

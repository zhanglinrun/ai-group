import { useEffect, useMemo, useState } from "react";
import { Check, Clock3, ShoppingCart, Users, WalletCards, Zap } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DEFAULT_GROUP_VALID_MINUTES, formatCountdown, PAYMENT_WINDOW_MS, secondsUntil } from "@/lib/countdown";
import { pricing } from "@/platform/pricing";
import { createPayQrOrder, type CreatePayOrderRequest, type PurchaseMode } from "@/platform/pay";
import { classifyPayPayload } from "@/lib/payPayload";
import { PaymentDialog, type PaymentDialogState } from "@/components/commerce/PaymentDialog";

interface PackageCard {
  code: string;
  name: string;
  price: number;
  quota: number;
  groupPrice?: number;
  groupDeduction?: number;
  activityId?: number;
  productId?: string;
  teamSize?: number;
  validTimeMinutes?: number;
}

interface GroupTeam {
  teamId: string;
  activityId?: number;
  targetCount?: number;
  completeCount?: number;
  lockCount?: number;
  validStartTime?: string;
  validEndTime?: string;
  validTimeCountdown?: string;
  outTradeNo?: string;
  own?: boolean;
}

function normalizeTeams(value: unknown): GroupTeam[] {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  return value.flatMap((item) => {
    if (!item || typeof item !== "object") return [];
    const row = item as Record<string, unknown>;
    const teamId = String(row.teamId || "");
    if (!teamId || seen.has(teamId)) return [];
    seen.add(teamId);
    return [{
      teamId,
      activityId: Number(row.activityId || 0) || undefined,
      targetCount: Number(row.targetCount || 0) || undefined,
      completeCount: Number(row.completeCount || 0) || 0,
      lockCount: Number(row.lockCount || 0) || 0,
      validStartTime: String(row.validStartTime || "") || undefined,
      validEndTime: String(row.validEndTime || "") || undefined,
      validTimeCountdown: String(row.validTimeCountdown || "") || undefined,
      outTradeNo: String(row.outTradeNo || "") || undefined,
    }];
  });
}

const FALLBACK_PACKAGES: PackageCard[] = [
  { code: "QUOTA_LIGHT", name: "轻享额度包", price: 12, quota: 60, groupPrice: 10.8, groupDeduction: 1.2, teamSize: 3, activityId: 100201, productId: "9890002", validTimeMinutes: DEFAULT_GROUP_VALID_MINUTES },
  { code: "QUOTA_STANDARD", name: "标准额度包", price: 60, quota: 300, groupPrice: 54, groupDeduction: 6, teamSize: 3, activityId: 100202, productId: "9890003", validTimeMinutes: DEFAULT_GROUP_VALID_MINUTES },
  { code: "QUOTA_LARGE", name: "大额额度包", price: 140, quota: 700, groupPrice: 126, groupDeduction: 14, teamSize: 3, activityId: 100203, productId: "9890004", validTimeMinutes: DEFAULT_GROUP_VALID_MINUTES },
];

function normalizePackages(payload: Record<string, unknown>): PackageCard[] {
  const raw = Array.isArray(payload.skus) ? payload.skus : [];
  const normalized = raw.flatMap((item) => {
    if (!item || typeof item !== "object") return [];
    const row = item as Record<string, unknown>;
    const code = String(row.code || row.productCode || "");
    if (!code) return [];
    const price = Number(row.price || 0);
    const groupPrice = Number(row.groupPayPrice);
    return [{
      code,
      name: String(row.name || code),
      price,
      quota: Number(row.baseQuota || row.base_quota || 0),
      groupPrice: Number.isFinite(groupPrice) && groupPrice > 0 ? groupPrice : price,
      groupDeduction: Number(row.groupDeductionPrice || row.group_deduction_price || 0) || 0,
      activityId: Number(row.groupActivityId || row.group_activity_id || 0) || undefined,
      productId: String(row.groupGoodsId || row.id || "") || undefined,
      teamSize: Number(row.teamSize || row.groupTeamSize || 0) || undefined,
      validTimeMinutes: Number(row.groupValidTime || row.validTime || 0) || DEFAULT_GROUP_VALID_MINUTES,
    }];
  });
  return normalized.length ? normalized : FALLBACK_PACKAGES;
}

function mergeHallTeams(mine: GroupTeam[], others: GroupTeam[]): GroupTeam[] {
  const ownIds = new Set(mine.map((team) => team.teamId));
  const seen = new Set<string>();
  const merged: GroupTeam[] = [];
  for (const team of [...mine, ...others]) {
    if (seen.has(team.teamId)) continue;
    seen.add(team.teamId);
    merged.push({ ...team, own: ownIds.has(team.teamId) });
  }
  return merged;
}

export function GroupHallPage(): JSX.Element {
  const navigate = useNavigate();
  const [packages, setPackages] = useState(FALLBACK_PACKAGES);
  const [teams, setTeams] = useState<GroupTeam[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [paying, setPaying] = useState<string | null>(null);
  const [payment, setPayment] = useState<PaymentDialogState | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let alive = true;
    const loadHall = async (silent = false): Promise<void> => {
      if (!silent) setMessage(null);
      try {
        const payload = await pricing();
        if (!alive) return;
        setPackages(normalizePackages(payload));
        const groupBuy = payload.groupBuy;
        const groupBuyData = groupBuy && typeof groupBuy === "object"
          ? groupBuy as Record<string, unknown>
          : {};
        if (groupBuyData.unavailable === true) {
          if (!silent) {
            setMessage("拼团大厅暂时读不到进行中队伍，请稍后刷新。");
          }
        } else {
          setMessage(null);
        }
        setTeams(mergeHallTeams(
          normalizeTeams(groupBuyData.myTeamList),
          normalizeTeams(groupBuyData.teamList),
        ));
      } catch {
        if (!alive) return;
        setPackages(FALLBACK_PACKAGES);
        setTeams([]);
      }
    };
    void loadHall();
    const refreshTimer = window.setInterval(() => void loadHall(true), 15_000);
    const clockTimer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => {
      alive = false;
      window.clearInterval(refreshTimer);
      window.clearInterval(clockTimer);
    };
  }, []);

  const totalQuota = useMemo(() => packages.reduce((sum, item) => sum + item.quota, 0), [packages]);

  async function purchase(packageCard: PackageCard, mode: PurchaseMode, teamId?: string): Promise<void> {
    if (teamId && teams.some((team) => team.teamId === teamId && team.own)) {
      setMessage("不能加入自己的队伍，请到订单中心继续支付。");
      return;
    }
    const key = teamId ? `${packageCard.code}-group-${teamId}` : `${packageCard.code}-${mode}`;
    setPaying(key);
    setMessage(null);
    try {
      const request: CreatePayOrderRequest = {
        requestId: `web-${crypto.randomUUID()}`,
        productId: packageCard.productId || packageCard.code,
        productCode: packageCard.code,
        marketType: (mode === "group" ? 1 : 0) as 0 | 1,
      };
      if (mode === "group" && packageCard.activityId) {
        request.activityId = packageCard.activityId;
      }
      if (mode === "group" && teamId) {
        request.teamId = teamId;
      }
      const order = await createPayQrOrder(request);
      const joined = teamId ? teams.find((team) => team.teamId === teamId) : undefined;
      const validMinutes = packageCard.validTimeMinutes || DEFAULT_GROUP_VALID_MINUTES;
      const qrPayload = classifyPayPayload(order.qrCode ?? order.payUrl);
      const formPayload = classifyPayPayload(order.payUrl);
      setPayment({
        orderId: order.orderId,
        title: packageCard.name,
        amount: order.amount ?? (mode === "group" ? packageCard.groupPrice : packageCard.price),
        qrCode: qrPayload.kind === "qr" ? qrPayload.qrCode : undefined,
        payUrl: formPayload.kind === "form" ? formPayload.formHtml : undefined,
        purchaseMode: mode,
        paymentExpiresAt: Date.now() + PAYMENT_WINDOW_MS,
        groupExpiresAt: mode === "group"
          ? (joined?.validEndTime ?? Date.now() + validMinutes * 60 * 1000)
          : undefined,
      });
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "创建支付订单失败");
    } finally {
      setPaying(null);
    }
  }

  function handlePaid(): void {
    setPayment(null);
    setMessage("支付成功，订单正在推进结算；可到订单中心查看最新状态。");
    navigate("/orders");
  }

  return (
    <div className="space-y-8">
      <div className="flex flex-col justify-between gap-4 md:flex-row md:items-end">
        <div>
          <Link to="/app" className="text-sm text-foreground-muted hover:text-primary">← 返回工作台</Link>
          <p className="mt-4 text-sm font-medium text-primary">熊博士积分商城</p>
          <h1 className="mt-2 text-3xl font-semibold">拼团大厅</h1>
          <p className="mt-3 max-w-2xl text-sm text-foreground-muted">拼团购买会发起一个新队伍；下方展示全部进行中队伍。自己的队伍只可查看，不能再次加入。支付后可在订单中心查看锁单、成团和入账进度。</p>
        </div>
        <div className="rounded-xl border border-border bg-surface px-5 py-4 text-sm"><p className="text-foreground-muted">当前可选积分</p><p className="mt-1 text-2xl font-semibold text-primary">{totalQuota.toLocaleString()}+</p></div>
      </div>
      {message ? <div className="whitespace-pre-line rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning-foreground">{message} <Link className="ml-1 underline" to="/orders">查看订单</Link></div> : null}
      <div className="grid gap-5 lg:grid-cols-3">
        {packages.map((item, index) => <PackageCard key={item.code} item={item} featured={index === 1} paying={paying} onPurchase={(mode) => void purchase(item, mode)} />)}
      </div>
      {teams.length > 0 ? <section className="space-y-3"><div><h2 className="text-xl font-semibold">进行中拼团</h2><p className="mt-1 text-sm text-foreground-muted">这里展示全部进行中队伍。选择同一套餐可加入他人队伍；自己的队伍不能再次加入，可到订单中心继续支付。</p></div><div className="grid gap-3 md:grid-cols-2">{teams.map((team) => <TeamProgressCard key={team.teamId} team={team} packages={packages} now={now} paying={paying} own={team.own} onJoin={team.own ? undefined : (item) => void purchase(item, "group", team.teamId)} />)}</div></section> : <Card><CardContent className="p-8 text-center"><p className="font-medium">暂无进行中拼团</p><p className="mt-2 text-sm text-foreground-muted">当前没有有效队伍；你可以点击额度包的“拼团购买（发起新团）”创建自己的队伍。</p></CardContent></Card>}
      <div className="grid gap-4 md:grid-cols-3">
        <Info icon={<Users />} title="真实拼团" text="同一活动共享队伍库存，成团后订单进入权益发放流程。" />
        <Info icon={<WalletCards />} title="积分账本" text="冻结、确认、释放和赠送均追加流水，重复回调不会重复入账。" />
        <Info icon={<Clock3 />} title="透明计费" text="输入每百万 Token 5 积分、输出 30 积分；研究页按实际 Token 展示最终扣费。" />
      </div>
      <PaymentDialog payment={payment} onClose={() => setPayment(null)} onPaid={handlePaid} />
    </div>
  );
}

function TeamProgressCard({
  team,
  packages,
  now,
  paying,
  own = false,
  onJoin,
}: {
  team: GroupTeam;
  packages: PackageCard[];
  now: number;
  paying?: string | null;
  own?: boolean;
  onJoin?: (item: PackageCard) => void;
}): JSX.Element {
  const item = packages.find((candidate) => candidate.activityId === team.activityId) ?? packages[0] ?? FALLBACK_PACKAGES[0];
  const teamPaying = paying === `${item.code}-group-${team.teamId}`;
  const complete = team.completeCount ?? 0;
  const target = team.targetCount ?? 3;
  const locked = team.lockCount ?? complete;
  const countdown = secondsUntil(team.validEndTime, now);
  const countdownText = formatCountdown(countdown) || team.validTimeCountdown || "等待成团";

  return <Card><CardContent className="space-y-3 p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-medium">{item.name}</p><p className="mt-1 text-xs text-foreground-muted">{own ? "我的队伍" : "队伍"} {team.teamId.slice(0, 12)} · 已支付 {complete}/{target} 人</p></div><span className={`rounded-full px-2 py-1 text-xs font-medium ${countdown === 0 ? "bg-secondary text-foreground-muted" : "bg-primary/10 text-primary"}`}><Clock3 className="mr-1 inline h-3 w-3" />{countdownText}</span></div><div className="h-2 overflow-hidden rounded-full bg-secondary"><div className="h-full rounded-full bg-primary transition-all" style={{ width: `${target > 0 ? Math.min(100, (locked / target) * 100) : 0}%` }} /></div><div className="flex items-center justify-between text-xs text-foreground-muted"><span>已锁单 {locked}/{target}</span><span>{Math.max(target - locked, 0) > 0 ? `还差 ${Math.max(target - locked, 0)} 人` : "已达到成团人数"}</span></div>{own ? <div className="grid gap-2"><Button size="sm" className="w-full" disabled>不能加入自己的队伍</Button><Button asChild variant="outline" className="w-full"><Link to="/orders">查看订单与支付状态</Link></Button></div> : <Button size="sm" className="w-full" onClick={() => onJoin?.(item)} disabled={Boolean(paying) || !item.activityId || countdown === 0}>{teamPaying ? "创建订单…" : "加入拼团"}</Button>}</CardContent></Card>;
}

function PackageCard({ item, featured, paying, onPurchase }: { item: PackageCard; featured: boolean; paying: string | null; onPurchase: (mode: PurchaseMode) => void }): JSX.Element {
  const groupPrice = item.groupPrice ?? item.price;
  const directLoading = paying === `${item.code}-direct`;
  const groupLoading = paying === `${item.code}-group`;
  const hasGroup = Boolean(item.activityId);
  return <Card className={featured ? "border-primary/50 shadow-raised" : ""}>
    <CardHeader><div className="flex items-center justify-between"><CardTitle>{item.name}</CardTitle>{featured ? <span className="rounded-full bg-primary/10 px-2 py-1 text-xs font-medium text-primary">推荐</span> : null}</div><p className="text-sm text-foreground-muted">{item.quota.toLocaleString()} 积分额度 · 按 Token 用量扣减 · 永久有效</p></CardHeader>
    <CardContent className="space-y-5">
      <div className="grid grid-cols-2 gap-3 rounded-lg border border-border bg-page/60 p-3">
        <div><p className="text-xs text-foreground-muted">直接购买</p><p className="mt-1 text-2xl font-semibold">¥{item.price.toFixed(2)}</p></div>
        <div><p className="text-xs text-foreground-muted">拼团购买</p><p className="mt-1 text-2xl font-semibold text-primary">¥{groupPrice.toFixed(2)}</p>{groupPrice < item.price ? <p className="text-xs text-foreground-muted line-through">¥{item.price.toFixed(2)}</p> : null}</div>
      </div>
      <ul className="space-y-2 text-sm text-foreground-muted"><li className="flex gap-2"><Check className="h-4 w-4 text-success" />直购支付后立即入账</li><li className="flex gap-2"><Check className="h-4 w-4 text-success" />拼团成团后自动入账</li><li className="flex gap-2"><Check className="h-4 w-4 text-success" />Token 明细可查</li></ul>
      <div className="grid gap-2 sm:grid-cols-2"><Button variant="outline" onClick={() => onPurchase("direct")} disabled={Boolean(paying)}><Zap className="mr-1 h-4 w-4" />{directLoading ? "创建订单…" : "直接购买"}</Button><Button onClick={() => onPurchase("group")} disabled={Boolean(paying) || !hasGroup}><ShoppingCart className="mr-1 h-4 w-4" />{groupLoading ? "创建订单…" : hasGroup ? "拼团购买（发起新团）" : "拼团暂不可用"}</Button></div>
    </CardContent>
  </Card>;
}

function Info({ icon, title, text }: { icon: JSX.Element; title: string; text: string }): JSX.Element {
  return <Card><CardContent className="flex gap-3 p-4"><div className="rounded-lg bg-primary/10 p-2 text-primary">{icon}</div><div><p className="font-medium">{title}</p><p className="mt-1 text-sm text-foreground-muted">{text}</p></div></CardContent></Card>;
}

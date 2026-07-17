import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { Loader2, RefreshCw, X } from 'lucide-react';
import QRCode from 'qrcode';
import { payApi } from '@/services/pay';
import { formatPrice } from '@/utils/tradeDisplay';
import { showMessage } from '@/utils';

export type QrPayment = {
  orderId: string;
  qrCode?: string | null;
  title: string;
  amount?: number;
  demoCompletionEnabled?: boolean;
  purchaseMode?: 'direct' | 'group';
};

type PaymentQrDialogProps = {
  payment: QrPayment | null;
  onClose: () => void;
  onPaid: () => void;
};

const PAY_WINDOW_SECONDS = 300; // 本地展示的支付时限（到期后停止轮询，可刷新重开）
const POLL_INTERVAL_MS = 3000;

function formatCountdown(seconds: number): string {
  const s = Math.max(seconds, 0);
  const mm = String(Math.floor(s / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

const PaymentQrDialog = memo(({ payment, onClose, onPaid }: PaymentQrDialogProps) => {
  const [qrImage, setQrImage] = useState<string>('');
  const [remaining, setRemaining] = useState(PAY_WINDOW_SECONDS);
  const [checking, setChecking] = useState(false);
  const [demoCompleting, setDemoCompleting] = useState(false);
  const [groupDemoPaid, setGroupDemoPaid] = useState(false);
  const settledRef = useRef(false);
  const statusRequestRef = useRef(false);

  const orderId = payment?.orderId;
  const qrCode = payment?.qrCode;

  // 生成二维码图片（把支付宝 qr_code 串渲染成 PNG dataURL）
  useEffect(() => {
    let alive = true;
    if (!qrCode) {
      setQrImage('');
      return;
    }
    QRCode.toDataURL(qrCode, { width: 220, margin: 1 })
      .then((url) => {
        if (alive) setQrImage(url);
      })
      .catch(() => {
        if (alive) setQrImage('');
      });
    return () => {
      alive = false;
    };
  }, [qrCode]);

  // 每次打开新订单重置状态
  useEffect(() => {
    settledRef.current = false;
    setGroupDemoPaid(false);
    setRemaining(PAY_WINDOW_SECONDS);
  }, [orderId]);

  const checkStatus = useCallback(
    async (silent: boolean) => {
      if (!orderId || settledRef.current || statusRequestRef.current) return;
      statusRequestRef.current = true;
      if (!silent) setChecking(true);
      try {
        const result = await payApi.syncSettle(orderId);
        if (typeof result === 'string' && result.startsWith('SETTLED')) {
          settledRef.current = true;
          showMessage()?.success('支付成功');
          onPaid();
        } else if (!silent) {
          showMessage()?.info('尚未检测到支付，请扫码完成后再刷新');
        }
      } catch (error) {
        if (!silent) {
          console.error('查询支付状态失败', error);
          showMessage()?.error('支付状态查询失败，请稍后重试');
        }
      } finally {
        statusRequestRef.current = false;
        if (!silent) setChecking(false);
      }
    },
    [orderId, onPaid],
  );

  const completeDemoPayment = useCallback(async () => {
    if (!orderId || settledRef.current || statusRequestRef.current) return;
    statusRequestRef.current = true;
    setDemoCompleting(true);
    try {
      const result = await payApi.completeDemoPayment(orderId);
      if (result !== 'SETTLED' && result !== 'GROUP_FINALIZED') {
        throw new Error('unexpected demo payment result');
      }
      settledRef.current = true;
      showMessage()?.success(
        result === 'GROUP_FINALIZED' ? '本地演示支付完成，拼团权益发放中' : '本地演示支付完成',
      );
      // team_success -> pay -> member 由 MQ 最终一致，给消费者一个很短的收敛窗口。
      await new Promise((resolve) => window.setTimeout(resolve, 1200));
      onPaid();
    } catch (error) {
      console.error('本地演示支付失败', error);
      showMessage()?.error('本地演示支付失败，请稍后重试');
    } finally {
      statusRequestRef.current = false;
      setDemoCompleting(false);
    }
  }, [orderId, onPaid]);

  const markDemoGroupPaid = useCallback(async () => {
    if (!orderId || settledRef.current || statusRequestRef.current) return;
    statusRequestRef.current = true;
    setDemoCompleting(true);
    try {
      const result = await payApi.markDemoGroupPaid(orderId);
      if (result === 'GROUP_FINALIZED') {
        settledRef.current = true;
        showMessage()?.success('该拼团已完成结算');
        onPaid();
        return;
      }
      if (result !== 'GROUP_WAITING') {
        throw new Error('unexpected demo group payment result');
      }
      setGroupDemoPaid(true);
      showMessage()?.success('模拟支付成功，队伍继续开放参团');
    } catch (error) {
      console.error('本地演示拼团支付失败', error);
      showMessage()?.error('本地演示拼团支付失败，请稍后重试');
    } finally {
      statusRequestRef.current = false;
      setDemoCompleting(false);
    }
  }, [orderId, onPaid]);

  const finalizeDemoGroup = useCallback(async () => {
    if (!orderId || settledRef.current || statusRequestRef.current || !groupDemoPaid) return;
    statusRequestRef.current = true;
    setDemoCompleting(true);
    try {
      const result = await payApi.finalizeDemoGroup(orderId);
      if (result !== 'GROUP_FINALIZED') {
        throw new Error('unexpected demo group finalization result');
      }
      settledRef.current = true;
      showMessage()?.success('拼团已封团，权益发放中');
      await new Promise((resolve) => window.setTimeout(resolve, 1200));
      onPaid();
    } catch (error) {
      console.error('本地演示封团失败', error);
      showMessage()?.error('本地演示封团失败，请确认其他成员已完成参团支付');
    } finally {
      statusRequestRef.current = false;
      setDemoCompleting(false);
    }
  }, [groupDemoPaid, orderId, onPaid]);

  // 轮询支付状态 + 本地倒计时
  useEffect(() => {
    // Pure local-demo orders have no Alipay QR and should not hammer the sandbox status API.
    if (!orderId || (payment?.demoCompletionEnabled && !qrCode)) return;
    const poll = setInterval(() => {
      if (settledRef.current) return;
      void checkStatus(true);
    }, POLL_INTERVAL_MS);
    const tick = setInterval(() => {
      setRemaining((prev) => {
        if (prev <= 1) {
          clearInterval(poll);
          clearInterval(tick);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => {
      clearInterval(poll);
      clearInterval(tick);
    };
  }, [orderId, qrCode, payment?.demoCompletionEnabled, checkStatus]);

  if (!payment) return null;

  const expired = remaining <= 0;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/45 px-4 py-6 backdrop-blur-sm">
      <div className="w-full max-w-sm rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)] p-6 shadow-[var(--shadow-lg)]">
        <div className="mb-4 flex items-center justify-between">
          <div className="text-base font-medium">支付订单</div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-[var(--chat-border)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex flex-col items-center">
          <div className="rounded-2xl border border-[var(--chat-border)] bg-white p-3">
            {qrImage ? (
              <img src={qrImage} alt="支付二维码" className="h-[200px] w-[200px]" />
            ) : (
              <div className="flex h-[200px] w-[200px] flex-col items-center justify-center gap-2 text-center text-xs text-[var(--chat-text-soft)]">
                {qrCode ? (
                  <Loader2 className="h-6 w-6 animate-spin" />
                ) : (
                  <span className="px-4">
                    {payment.demoCompletionEnabled
                      ? '支付宝已关闭，请使用下方本地演示按钮推进完整业务闭环'
                      : '支付暂不可用，请稍后重试或到订单记录使用「去支付」'}
                  </span>
                )}
              </div>
            )}
          </div>

          <div className="mt-3 text-sm text-[var(--chat-text-soft)]">
            {payment.demoCompletionEnabled && !qrCode
              ? '本地演示支付（不会调用支付宝）'
              : '请使用支付宝扫码支付'}
          </div>
          {payment.amount != null ? (
            <div className="mt-1 text-2xl font-semibold text-[var(--chat-text)]">
              {formatPrice(payment.amount)}
            </div>
          ) : null}
          <div
            className={`mt-1 text-xs ${expired ? 'text-red-600' : 'text-[var(--chat-text-soft)]'}`}
          >
            {expired
              ? '二维码已过期，请关闭后重新下单'
              : `剩余支付时间 ${formatCountdown(remaining)}`}
          </div>
        </div>

        <div className="mt-4 space-y-1.5 rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-3 text-xs">
          <div className="flex justify-between">
            <span className="text-[var(--chat-text-soft)]">商品</span>
            <span className="max-w-[60%] truncate font-medium">{payment.title}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-[var(--chat-text-soft)]">订单号</span>
            <span className="font-medium">{payment.orderId}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-[var(--chat-text-soft)]">状态</span>
            <span className={`font-medium ${groupDemoPaid ? 'text-emerald-700' : 'text-amber-700'}`}>
              {groupDemoPaid ? '已支付，待封团' : '待支付'}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-[var(--chat-text-soft)]">支付方式</span>
            <span className="font-medium">
              {payment.demoCompletionEnabled && !qrCode ? '本地演示' : '支付宝'}
            </span>
          </div>
        </div>

        <div className="mt-4 flex items-center gap-3">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex flex-1 items-center justify-center rounded-full border border-[var(--chat-border)] px-4 py-2.5 text-sm font-medium"
          >
            关闭
          </button>
          <button
            type="button"
            onClick={() => void checkStatus(false)}
            disabled={checking || expired}
            className="inline-flex flex-1 items-center justify-center gap-2 rounded-full bg-[var(--chat-text)] px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60"
          >
            {checking ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            刷新状态
          </button>
        </div>
        {payment.demoCompletionEnabled ? (
          <div className="mt-3 rounded-2xl border border-amber-200 bg-amber-50 p-3 text-amber-900">
            <div className="text-xs font-medium">本地演示模式</div>
            {payment.purchaseMode === 'group' ? (
              <>
                <div className="mt-1 text-[11px] leading-5">
                  先登记当前成员已支付，队伍仍保持开放；使用第二个账号参团并支付后，再从任一已支付订单显式封团，随后由真实
                  group 结算并通过 MQ 发放权益。
                </div>
                <button
                  type="button"
                  onClick={() => void markDemoGroupPaid()}
                  disabled={checking || demoCompleting || expired || groupDemoPaid}
                  className="mt-2 inline-flex w-full items-center justify-center gap-2 rounded-full bg-amber-700 px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60"
                >
                  {demoCompleting && !groupDemoPaid ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                  {groupDemoPaid ? '当前成员已支付' : '模拟支付并等待参团'}
                </button>
                {groupDemoPaid ? (
                  <button
                    type="button"
                    onClick={() => void finalizeDemoGroup()}
                    disabled={checking || demoCompleting}
                    className="mt-2 inline-flex w-full items-center justify-center gap-2 rounded-full border border-amber-700 bg-white px-4 py-2.5 text-sm font-medium text-amber-800 disabled:opacity-60"
                  >
                    {demoCompleting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                    第二个成员支付后，封团并结算
                  </button>
                ) : null}
              </>
            ) : (
              <>
                <div className="mt-1 text-[11px] leading-5">
                  以下按钮仅用于本地直购演示：模拟付款后进入真实订单结算与 MQ 权益发放链路，不会调用支付宝。
                </div>
                <button
                  type="button"
                  onClick={() => void completeDemoPayment()}
                  disabled={checking || demoCompleting || expired}
                  className="mt-2 inline-flex w-full items-center justify-center gap-2 rounded-full bg-amber-700 px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60"
                >
                  {demoCompleting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                  模拟完成支付并推进业务闭环
                </button>
              </>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
});

PaymentQrDialog.displayName = 'PaymentQrDialog';

export default PaymentQrDialog;

import { useCallback, useRef, useState } from 'react';
import { payApi } from '@/services/pay';
import { getAuthUserId } from '@/auth/token';
import { DEFAULT_GOODS_ID, DIRECT_BUY_MARKET_TYPE, GROUP_BUY_MARKET_TYPE } from '@/constants/trade';
import { skuDisplayName } from '@/utils/tradeDisplay';
import type { GroupBuyInfo, SkuItem } from '@/services/bff';
import type { QrPayment } from '@/components/trade/PaymentQrDialog';
import { showMessage } from '@/utils';

type PurchaseMode = 'direct' | 'group';

export function createPayRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `pay-${crypto.randomUUID()}`;
  }
  return `pay-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function useTradePurchase(groupBuy: GroupBuyInfo | null) {
  const [buyingKey, setBuyingKey] = useState('');
  const inFlightAttempts = useRef(new Map<string, Promise<boolean>>());
  // 扫码支付：下单成功后展示支付宝当面付二维码，轮询到账后由调用页处理跳转/刷新
  const [qrPayment, setQrPayment] = useState<QrPayment | null>(null);

  const handleBuy = useCallback(
    async (sku: SkuItem, mode: PurchaseMode, teamId?: string) => {
      const userId = getAuthUserId();
      if (!userId) {
        showMessage()?.error('请先登录');
        return false;
      }

      const isGroup = mode === 'group';
      // 优先使用额度包自己的拼团活动，回退到页面级默认活动
      const activityId = sku.groupActivityId ?? groupBuy?.activityId;
      if (isGroup && !activityId) {
        showMessage()?.error('拼团活动不可用');
        return false;
      }
      if (isGroup && teamId) {
        const team = groupBuy?.teamList?.find((candidate) => candidate.teamId === teamId);
        const teamActivityId = team?.activityId ?? groupBuy?.activityId;
        if (!team || (teamActivityId != null && teamActivityId !== activityId)) {
          showMessage()?.error('该拼团不属于当前额度包，请刷新后重试');
          return false;
        }
      }

      const key = `${sku.code}-${mode}${teamId ? `-${teamId}` : ''}`;
      const existingAttempt = inFlightAttempts.current.get(key);
      if (existingAttempt) {
        return existingAttempt;
      }

      // requestId 在本次 Promise 的所有传输重试/重复点击中保持不变；下一次显式购买才生成新值。
      const requestId = createPayRequestId();
      setBuyingKey(key);
      const attempt = (async () => {
        try {
          const order = await payApi.createQrOrder({
            requestId,
            userId: String(userId),
            productId: sku.groupGoodsId || groupBuy?.goods?.goodsId || DEFAULT_GOODS_ID,
            productCode: sku.code,
            activityId: isGroup ? activityId : undefined,
            marketType: isGroup ? GROUP_BUY_MARKET_TYPE : DIRECT_BUY_MARKET_TYPE,
            teamId: isGroup ? teamId : undefined,
          });
          if (!order?.orderId) {
            showMessage()?.error('下单失败，请稍后重试');
            return false;
          }
          const amount =
            order.amount ??
            (isGroup ? (sku.groupPayPrice ?? groupBuy?.goods?.payPrice ?? sku.price) : sku.price);
          setQrPayment({
            orderId: order.orderId,
            qrCode: order.qrCode,
            title: skuDisplayName(sku),
            amount,
            demoCompletionEnabled: order.demoCompletionEnabled,
            purchaseMode: mode,
          });
          return true;
        } catch (error) {
          console.error('创建支付订单失败', error);
          showMessage()?.error('购买失败，请稍后重试');
          return false;
        } finally {
          inFlightAttempts.current.delete(key);
          setBuyingKey((current) => (current === key ? '' : current));
        }
      })();
      inFlightAttempts.current.set(key, attempt);
      return attempt;
    },
    [groupBuy],
  );

  const closeQrPayment = useCallback(() => setQrPayment(null), []);

  return {
    buyingKey,
    handleBuy,
    qrPayment,
    closeQrPayment,
  };
}

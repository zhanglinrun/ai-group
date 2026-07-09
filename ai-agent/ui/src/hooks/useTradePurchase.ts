import { useCallback, useState } from "react";
import { message } from "antd";
import { payApi } from "@/services/pay";
import { getAuthUserId } from "@/auth/token";
import { submitAlipayForm } from "@/utils/payForm";
import {
  DEFAULT_GOODS_ID,
  DIRECT_BUY_MARKET_TYPE,
  GROUP_BUY_MARKET_TYPE,
} from "@/constants/trade";
import type { GroupBuyInfo, SkuItem } from "@/services/bff";

type PurchaseMode = "direct" | "group";

export function useTradePurchase(groupBuy: GroupBuyInfo | null) {
  const [buyingKey, setBuyingKey] = useState("");

  const handleBuy = useCallback(
    async (sku: SkuItem, mode: PurchaseMode, teamId?: string) => {
      const userId = getAuthUserId();
      if (!userId) {
        message.error("请先登录");
        return false;
      }

      const isGroup = mode === "group";
      // 优先用 SKU 自己的拼团活动（月卡/年卡/加油包各自独立），回退到页面级默认活动
      const activityId = sku.groupActivityId ?? groupBuy?.activityId;
      if (isGroup && !activityId) {
        message.error("拼团活动不可用");
        return false;
      }

      const key = `${sku.code}-${mode}${teamId ? `-${teamId}` : ""}`;
      setBuyingKey(key);
      try {
        const payHtml = await payApi.createOrder({
          userId: String(userId),
          productId: sku.groupGoodsId || groupBuy?.goods?.goodsId || DEFAULT_GOODS_ID,
          productCode: sku.code,
          activityId: isGroup ? activityId : undefined,
          marketType: isGroup ? GROUP_BUY_MARKET_TYPE : DIRECT_BUY_MARKET_TYPE,
          teamId: isGroup ? teamId : undefined,
        });
        submitAlipayForm(payHtml);
        return true;
      } catch (error) {
        console.error("创建支付订单失败", error);
        message.error("购买失败，请稍后重试");
        return false;
      } finally {
        setBuyingKey("");
      }
    },
    [groupBuy]
  );

  return {
    buyingKey,
    handleBuy
  };
}

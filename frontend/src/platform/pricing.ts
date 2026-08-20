import { platformClient } from "@/platform/client";

const DEFAULT_GOODS_ID = "9890002";
const GROUP_SOURCE = "s01";
const GROUP_CHANNEL = "c01";

interface DegradeError {
  service: string;
  code: string;
  message?: string;
}

function stringValue(value: unknown): string | null {
  if (value == null) return null;
  const text = String(value).trim();
  return text.length === 0 ? null : text;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function splitCurrentUserTeams(
  market: Record<string, unknown>,
  currentUserId: string | null,
): Record<string, unknown> {
  const teamList = asList(market.teamList);
  const currentUserTeams: unknown[] = [];
  const currentTeamIds = new Set<string>();
  for (const item of asList(market.myTeamList)) {
    const team = asRecord(item);
    const teamId = team ? stringValue(team.teamId) : null;
    if (teamId && currentTeamIds.add(teamId)) {
      currentUserTeams.push(item);
    }
  }
  const visibleTeams: unknown[] = [];
  for (const item of teamList) {
    const team = asRecord(item);
    if (!team) {
      visibleTeams.push(item);
      continue;
    }
    const teamId = stringValue(team.teamId);
    const ownerId = team.userId == null ? null : String(team.userId);
    const owned = Boolean(ownerId && currentUserId && ownerId === currentUserId);
    if ((teamId && currentTeamIds.has(teamId)) || owned) {
      if (!teamId || currentTeamIds.add(teamId)) {
        currentUserTeams.push(item);
      }
    } else {
      visibleTeams.push(item);
    }
  }
  return {
    ...market,
    myTeamList: currentUserTeams,
    teamList: visibleTeams,
  };
}

async function listSkusSafe(errors: DegradeError[]): Promise<Record<string, unknown>[]> {
  try {
    const { data } = await platformClient.get<{ data?: Record<string, unknown>[] }>("/api/member/skus");
    return Array.isArray(data.data) ? data.data : [];
  } catch (cause) {
    errors.push({
      service: "member",
      code: "SKU_LIST_UNAVAILABLE",
      message: cause instanceof Error ? cause.message : String(cause),
    });
    return [];
  }
}

async function queryGroupMarketSafe(
  goodsId: string,
  currentUserId: string | null,
  errors: DegradeError[],
): Promise<Record<string, unknown>> {
  try {
    const { data } = await platformClient.get<{ code?: string | number; data?: Record<string, unknown> }>(
      "/api/group/activities",
      { params: { source: GROUP_SOURCE, channel: GROUP_CHANNEL, goodsId } },
    );
    const payload = asRecord(data.data);
    if (!payload) {
      return { unavailable: true };
    }
    return splitCurrentUserTeams(payload, currentUserId);
  } catch (cause) {
    errors.push({
      service: "group",
      code: "GROUP_MARKET_UNAVAILABLE",
      message: cause instanceof Error ? cause.message : String(cause),
    });
    return { unavailable: true };
  }
}

async function queryGroupMarketsForSkus(
  skus: Record<string, unknown>[],
  currentUserId: string | null,
  errors: DegradeError[],
): Promise<Map<string, Record<string, unknown>>> {
  const marketByGoods = new Map<string, Record<string, unknown>>();
  const goodsIds: string[] = [];
  for (const sku of skus) {
    const goodsId = stringValue(sku.groupGoodsId);
    if (goodsId && !goodsIds.includes(goodsId)) {
      goodsIds.push(goodsId);
    }
  }
  if (goodsIds.length === 0) {
    goodsIds.push(DEFAULT_GOODS_ID);
  }
  await Promise.all(goodsIds.map(async (goodsId) => {
    marketByGoods.set(goodsId, await queryGroupMarketSafe(goodsId, currentUserId, errors));
  }));
  return marketByGoods;
}

function enrichSkusWithGroupBuy(
  skus: Record<string, unknown>[],
  marketByGoods: Map<string, Record<string, unknown>>,
): Record<string, unknown>[] {
  return skus.map((sku) => {
    const goodsId = stringValue(sku.groupGoodsId);
    if (!goodsId) {
      return sku;
    }
    const market = marketByGoods.get(goodsId);
    if (!market || market.unavailable === true) {
      return sku;
    }
    const goods = asRecord(market.goods);
    return {
      ...sku,
      groupPayPrice: goods?.payPrice,
      groupDeductionPrice: goods?.deductionPrice,
      groupOriginalPrice: goods?.originalPrice,
      groupActivityId: market.activityId,
      groupTeamSize: market.targetCount,
      groupValidTime: market.validTime,
    };
  });
}

function buildAggregatedGroupBuy(
  skus: Record<string, unknown>[],
  marketByGoods: Map<string, Record<string, unknown>>,
): Record<string, unknown> {
  const mergedTeams: unknown[] = [];
  const mergedMyTeams: unknown[] = [];
  const orderedGoods: string[] = [];
  for (const sku of skus) {
    const goodsId = stringValue(sku.groupGoodsId);
    if (goodsId && !orderedGoods.includes(goodsId)) {
      orderedGoods.push(goodsId);
    }
  }
  if (orderedGoods.length === 0) {
    orderedGoods.push(...marketByGoods.keys());
  }
  let aggregated: Record<string, unknown> | null = null;
  for (const goodsId of orderedGoods) {
    const market = marketByGoods.get(goodsId);
    if (!market || market.unavailable === true) {
      continue;
    }
    if (!aggregated) {
      aggregated = { ...market };
    }
    mergedTeams.push(...asList(market.teamList));
    mergedMyTeams.push(...asList(market.myTeamList));
  }
  if (!aggregated) {
    return { unavailable: true };
  }
  return {
    ...aggregated,
    teamList: mergedTeams,
    myTeamList: mergedMyTeams,
  };
}

export async function pricing(): Promise<Record<string, unknown>> {
  const errors: DegradeError[] = [];
  let currentUserId: string | null = null;
  try {
    const { data } = await platformClient.get<{ data?: { id?: number } }>("/api/auth/me");
    currentUserId = data.data?.id == null ? null : String(data.data.id);
  } catch {
    currentUserId = null;
  }
  const skus = await listSkusSafe(errors);
  const marketByGoods = await queryGroupMarketsForSkus(skus, currentUserId, errors);
  return {
    skus: enrichSkusWithGroupBuy(skus, marketByGoods),
    groupBuy: buildAggregatedGroupBuy(skus, marketByGoods),
    meta: {
      degraded: errors.length > 0,
      errors,
    },
  };
}

import { describe, expect, it } from "vitest";

import {
  paymentOutcomeMessage,
  resolvePaymentOutcome,
} from "./paymentStatus";

describe("paymentStatus", () => {
  it("Pro 已生效时返回 pro-active", () => {
    expect(
      resolvePaymentOutcome({
        tier: "PRO",
        expireAt: new Date(Date.now() + 86_400_000).toISOString(),
      })
    ).toBe("pro-active");
  });

  it("Free 或待成团时返回 paid-waiting-group", () => {
    expect(resolvePaymentOutcome({ tier: "FREE" })).toBe("paid-waiting-group");
    expect(
      resolvePaymentOutcome({
        tier: "PRO",
        expireAt: new Date(Date.now() - 86_400_000).toISOString(),
      })
    ).toBe("paid-waiting-group");
  });

  it("文案区分等待成团与 Pro 生效", () => {
    expect(paymentOutcomeMessage("paid-waiting-group")).toContain("等待拼团");
    expect(paymentOutcomeMessage("pro-active")).toContain("Pro 会员已生效");
  });
});

"""Read-only deterministic utility tools exposed through MCP."""

from __future__ import annotations

from typing import Annotated

from mcp.server.fastmcp import FastMCP
from pydantic import Field

from ._result import bounded_json, error_json


MIN_OUTPUT_TOKENS = 256
MAX_TOKEN_COUNT = 2_000_000_000
MAX_RATE = 1_000_000_000
JAVA_LONG_MAX = 9_223_372_036_854_775_807

mcp = FastMCP(
    "ai-group-agent-utility",
    instructions=(
        "提供无副作用的 Agent 额度计算工具。"
        "工具只进行有界整数运算，不访问网络、文件或进程。"
    ),
)


TokenCount = Annotated[
    int,
    Field(ge=0, le=MAX_TOKEN_COUNT, description="非负 Token 数量"),
]
PositiveRate = Annotated[
    int,
    Field(ge=1, le=MAX_RATE, description="每 Token 对应的整数微额度"),
]


def _valid_non_negative_int(value: object, upper_bound: int) -> bool:
    return not isinstance(value, bool) and isinstance(value, int) and 0 <= value <= upper_bound


def _valid_positive_rate(value: object) -> bool:
    return not isinstance(value, bool) and isinstance(value, int) and 1 <= value <= MAX_RATE


def _within_java_long(*values: int) -> bool:
    return all(0 <= value <= JAVA_LONG_MAX for value in values)


@mcp.tool(
    description="按项目实际使用的整数微额度公式估算 LLM 预留、最低预留与实际结算。",
)
def utility_estimate_llm_quota(
    input_tokens: TokenCount,
    requested_output_tokens: TokenCount,
    actual_output_tokens: TokenCount,
    input_microcredits_per_token: PositiveRate,
    output_microcredits_per_token: PositiveRate,
) -> str:
    """Mirror the Java quota formula with bounded integer arithmetic."""

    token_values = (input_tokens, requested_output_tokens, actual_output_tokens)
    if not all(_valid_non_negative_int(value, MAX_TOKEN_COUNT) for value in token_values):
        return error_json("invalid_argument", "Token 参数必须是 0 到 2000000000 的整数。")
    if not _valid_positive_rate(input_microcredits_per_token) or not _valid_positive_rate(
        output_microcredits_per_token
    ):
        return error_json("invalid_argument", "费率必须是 1 到 1000000000 的整数微额度。")

    input_charge = input_tokens * input_microcredits_per_token
    requested = input_charge + requested_output_tokens * output_microcredits_per_token
    minimum = input_charge + MIN_OUTPUT_TOKENS * output_microcredits_per_token
    actual = input_charge + actual_output_tokens * output_microcredits_per_token
    if not _within_java_long(input_charge, requested, minimum, actual):
        return error_json("arithmetic_overflow", "计算结果超出 Java long 范围。")

    return bounded_json(
        {
            "ok": True,
            "unit": "microcredits",
            "requested_microcredits": requested,
            "minimum_microcredits": minimum,
            "actual_microcredits": actual,
            "within_requested_reservation": actual <= requested,
            "inputs": {
                "input_tokens": input_tokens,
                "requested_output_tokens": requested_output_tokens,
                "actual_output_tokens": actual_output_tokens,
                "input_microcredits_per_token": input_microcredits_per_token,
                "output_microcredits_per_token": output_microcredits_per_token,
            },
        }
    )


@mcp.tool(
    description="解释项目 LLM 额度预留与结算公式及其单位。",
)
def utility_explain_quota_formula() -> str:
    """Return the fixed quota formula without accepting any external input."""

    return bounded_json(
        {
            "ok": True,
            "unit": "microcredits",
            "minimum_output_tokens": MIN_OUTPUT_TOKENS,
            "formulas": {
                "requested_microcredits": "input_tokens * input_rate + requested_output_tokens * output_rate",
                "minimum_microcredits": "input_tokens * input_rate + 256 * output_rate",
                "actual_microcredits": "input_tokens * input_rate + actual_output_tokens * output_rate",
            },
            "note": (
                "系统先尝试按 requested 额度预留；余额不足时可降到 minimum，"
                "最终按实际 Token 结算并受预留上限约束。"
            ),
        }
    )


def main() -> None:
    """Run the official FastMCP STDIO transport."""

    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()

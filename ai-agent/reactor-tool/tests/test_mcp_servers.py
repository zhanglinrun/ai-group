from __future__ import annotations

import json
import sys

import anyio
from mcp import ClientSession, StdioServerParameters, types
from mcp.client.stdio import stdio_client

from reactor_tool.mcp_servers._result import MAX_RESULT_BYTES, bounded_json
from reactor_tool.mcp_servers.agent_utility_server import (
    utility_estimate_llm_quota,
    utility_explain_quota_formula,
)
from reactor_tool.mcp_servers.project_knowledge_server import (
    project_get_flow,
    project_search_knowledge,
)


def _decoded(result: str) -> dict:
    assert len(result.encode("utf-8")) <= MAX_RESULT_BYTES
    return json.loads(result)


def test_project_knowledge_search_and_flow_are_fixed_and_bounded() -> None:
    search = _decoded(project_search_knowledge("长期记忆", limit=3))
    assert search["ok"] is True
    assert search["count"] >= 1
    assert search["results"][0]["id"] == "memory-architecture"

    flow = _decoded(project_get_flow("purchase_to_chat"))
    assert flow["ok"] is True
    assert flow["flow_name"] == "purchase_to_chat"
    assert len(flow["steps"]) >= 5


def test_utility_quota_formula_matches_java_integer_arithmetic() -> None:
    estimate = _decoded(
        utility_estimate_llm_quota(
            input_tokens=1000,
            requested_output_tokens=512,
            actual_output_tokens=100,
            input_microcredits_per_token=5,
            output_microcredits_per_token=30,
        )
    )
    assert estimate["requested_microcredits"] == 20_360
    assert estimate["minimum_microcredits"] == 12_680
    assert estimate["actual_microcredits"] == 8_000
    assert estimate["within_requested_reservation"] is True

    explanation = _decoded(utility_explain_quota_formula())
    assert explanation["minimum_output_tokens"] == 256


def test_all_results_remain_valid_json_within_eight_kibibytes() -> None:
    oversized = bounded_json({"content": "记忆" * 10_000})
    decoded = _decoded(oversized)
    assert decoded["ok"] is False
    assert decoded["error"]["code"] == "result_too_large"


def _assert_safe_tool_schema(tool: types.Tool, prefix: str) -> None:
    assert tool.name.startswith(prefix)
    properties = tool.inputSchema.get("properties", {})
    lowered_property_names = {name.casefold() for name in properties}
    assert not any(
        forbidden in property_name
        for property_name in lowered_property_names
        for forbidden in ("path", "url", "command")
    )


async def _exercise_stdio_server(
    module: str,
    prefix: str,
    tool_name: str,
    arguments: dict,
) -> tuple[list[types.Tool], dict]:
    server = StdioServerParameters(
        command=sys.executable,
        args=["-m", module],
    )
    async with stdio_client(server) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            initialize_result = await session.initialize()
            assert initialize_result.serverInfo.name.startswith("ai-group-")
            tools_result = await session.list_tools()
            for tool in tools_result.tools:
                _assert_safe_tool_schema(tool, prefix)
            call_result = await session.call_tool(tool_name, arguments=arguments)
            assert call_result.isError is not True
            assert call_result.content
            first_content = call_result.content[0]
            assert isinstance(first_content, types.TextContent)
            payload = _decoded(first_content.text)
            assert payload["ok"] is True
            return tools_result.tools, payload


def test_official_client_completes_real_stdio_protocol_round_trip() -> None:
    async def scenario() -> None:
        project_tools, project_payload = await _exercise_stdio_server(
            "reactor_tool.mcp_servers.project_knowledge_server",
            "project_",
            "project_search_knowledge",
            {"query": "长期记忆", "limit": 3},
        )
        assert {tool.name for tool in project_tools} == {
            "project_search_knowledge",
            "project_get_flow",
        }
        assert project_payload["count"] >= 1

        utility_tools, utility_payload = await _exercise_stdio_server(
            "reactor_tool.mcp_servers.agent_utility_server",
            "utility_",
            "utility_estimate_llm_quota",
            {
                "input_tokens": 1000,
                "requested_output_tokens": 512,
                "actual_output_tokens": 100,
                "input_microcredits_per_token": 5,
                "output_microcredits_per_token": 30,
            },
        )
        assert {tool.name for tool in utility_tools} == {
            "utility_estimate_llm_quota",
            "utility_explain_quota_formula",
        }
        assert utility_payload["actual_microcredits"] == 8_000

    anyio.run(scenario)

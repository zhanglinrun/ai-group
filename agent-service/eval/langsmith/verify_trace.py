"""Verify that one Agent run produced a complete LangSmith trace tree."""

from __future__ import annotations

import argparse
import json
import os
from typing import Any

import httpx
from langsmith import Client


_BASE_REQUIRED_NODE_NAMES = {
    "intake_generate",
    "planner_generate",
    "supervisor",
    "analyst",
    "writer",
    "qa",
}


def _metadata(run: Any) -> dict[str, Any]:
    extra = getattr(run, "extra", None)
    if isinstance(extra, dict):
        metadata = extra.get("metadata")
        if isinstance(metadata, dict):
            return metadata
    metadata = getattr(run, "metadata", None)
    return metadata if isinstance(metadata, dict) else {}


def _node_name(run: Any) -> str | None:
    metadata_name = _metadata(run).get("node")
    if isinstance(metadata_name, str):
        return metadata_name
    inputs = getattr(run, "inputs", None)
    if isinstance(inputs, dict):
        input_name = inputs.get("node_name")
        if isinstance(input_name, str):
            return input_name
    return None


def _auth_headers() -> dict[str, str]:
    headers = {"X-Agent-Source": "eval"}
    token = os.getenv("AGENT_EVAL_INTERNAL_TOKEN") or os.getenv("AI_GROUP_INTERNAL_TOKEN")
    identity_jwt = os.getenv("AGENT_EVAL_IDENTITY_JWT")
    if token:
        headers["X-Internal-Token"] = token
    if identity_jwt:
        headers["X-Internal-Jwt"] = identity_jwt
    return headers


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify an Agent run in LangSmith.")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--project", default=os.getenv("LANGSMITH_PROJECT", "ai-group-agent"))
    parser.add_argument("--base-url", default=os.getenv("AGENT_EVAL_BASE_URL", "http://localhost:8090"))
    parser.add_argument("--local-only", action="store_true")
    args = parser.parse_args()

    local: dict[str, Any] = {}
    if not args.local_only:
        with httpx.Client(base_url=args.base_url.rstrip("/"), timeout=30.0) as client:
            response = client.get(
                f"/api/runs/{args.run_id}/trace",
                headers=_auth_headers(),
            )
            response.raise_for_status()
            local = response.json()

    result: dict[str, Any] = {
        "run_id": args.run_id,
        "project": args.project,
        "local_trace": {
            "step_count": len(local.get("steps", [])) if isinstance(local.get("steps"), list) else 0,
            "llm_call_count": len(local.get("llm_calls", [])) if isinstance(local.get("llm_calls"), list) else 0,
            "timeline_count": len(local.get("timeline", [])) if isinstance(local.get("timeline"), list) else 0,
        },
    }
    if args.local_only:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    client = Client()
    metadata_filter = (
        'and(eq(metadata_key, "run_id"), '
        f'eq(metadata_value, {json.dumps(args.run_id)}))'
    )
    def _runs_named(name: str) -> list[Any]:
        return list(
            client.list_runs(
                project_name=args.project,
                filter=f'and({metadata_filter}, eq(name, {json.dumps(name)}))',
                limit=100,
            )
        )

    roots = _runs_named("agent.langgraph.run")
    node_runs = _runs_named("agent.langgraph.node")
    llm_runs = _runs_named("agent.llm.call")
    node_names = {
        node_name
        for run in node_runs
        for node_name in [_node_name(run)]
        if isinstance(node_name, str)
    }
    local_agent_names = {
        str(item.get("agent_name"))
        for item in local.get("steps", [])
        if isinstance(item, dict) and item.get("agent_name")
    }
    required_node_names = set(_BASE_REQUIRED_NODE_NAMES)
    if "discovery" in local_agent_names:
        required_node_names.update({"discovery", "replanner"})
    if "researcher" in local_agent_names:
        required_node_names.add("researcher")
    # LangGraph's tracing integration normalizes the node field to
    # `langgraph_node`; custom traceable metadata may remain under `node`.
    # Both are explicit node metadata and are safe to accept here.
    node_metadata_missing_count = sum(
        1
        for run in node_runs
        if not isinstance(_metadata(run).get("node"), str)
        and not isinstance(_metadata(run).get("langgraph_node"), str)
    )
    missing_nodes = sorted(required_node_names - node_names)
    llm_metadata_missing = [
        str(getattr(run, "id", "unknown"))
        for run in llm_runs
        if not isinstance(_metadata(run).get("provider"), str)
        or not isinstance(_metadata(run).get("model_name"), str)
    ]
    metadata = _metadata(roots[0]) if roots else {}
    missing_root_metadata = [
        key
        for key in ("run_id", "research_mode", "report_depth")
        if not metadata.get(key)
    ]
    result["langsmith"] = {
        "matching_run_count": len(roots) + len(node_runs) + len(llm_runs),
        "root_count": len(roots),
        "node_count": len(node_runs),
        "node_names": sorted(node_names),
        "required_node_names": sorted(required_node_names),
        "missing_required_nodes": missing_nodes,
        "node_metadata_missing_count": node_metadata_missing_count,
        "llm_count": len(llm_runs),
        "llm_metadata_missing_count": len(llm_metadata_missing),
        "missing_root_metadata": missing_root_metadata,
        "root_metadata": metadata,
        "url": getattr(roots[0], "url", None) if roots else None,
    }
    result["passed"] = bool(
        roots
        and not missing_nodes
        and node_metadata_missing_count == 0
        and llm_runs
        and not llm_metadata_missing
        and not missing_root_metadata
        and metadata.get("run_id") == args.run_id
        and result["local_trace"]["step_count"] > 0
        and result["local_trace"]["llm_call_count"] > 0
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["passed"]:
        raise SystemExit("LangSmith trace verification failed")


if __name__ == "__main__":
    main()

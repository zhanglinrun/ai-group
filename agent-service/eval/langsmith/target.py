from __future__ import annotations

import os
import time
from typing import Any

import httpx


def _timeout_seconds(inputs: dict[str, Any]) -> float:
    base_timeout = float(os.getenv("AGENT_EVAL_TIMEOUT_SECONDS", "900"))
    if str(inputs.get("report_depth") or "quick").strip().lower() != "deep":
        return base_timeout
    deep_timeout = os.getenv("AGENT_EVAL_DEEP_TIMEOUT_SECONDS")
    if deep_timeout:
        return float(deep_timeout)
    return max(base_timeout, 1800.0)


def _headers() -> dict[str, str]:
    token = os.getenv("AGENT_EVAL_INTERNAL_TOKEN") or os.getenv("AI_GROUP_INTERNAL_TOKEN")
    identity_jwt = os.getenv("AGENT_EVAL_IDENTITY_JWT")
    headers: dict[str, str] = {}
    if token:
        headers["X-Internal-Token"] = token
    if identity_jwt:
        # The Gateway converts the external session into this internal header;
        # direct Agent evaluation calls must use the same contract.
        headers["X-Internal-Jwt"] = identity_jwt
    return headers


def _role_for_mode(inputs: dict[str, Any]) -> str:
    mode = str(inputs.get("research_mode") or "general").strip().lower()
    return {
        "academic": "researcher",
        "technical": "engineer",
        "commercial": "pm",
        "general": "researcher",
    }.get(mode, "researcher")


def _reply_for_targets(inputs: dict[str, Any], targets: list[str]) -> dict[str, Any]:
    target_set = set(targets)
    if "user_role" in target_set:
        return {"text": "", "selected_options": [_role_for_mode(inputs)]}
    if "analysis_intent" in target_set:
        return {"text": str(inputs.get("analysis_intent") or inputs.get("user_query", "")), "selected_options": []}
    if target_set.intersection({"competitors_explicit", "competitors_discovery_mode"}):
        competitors = [str(item).strip() for item in inputs.get("competitors", []) if str(item).strip()]
        if competitors:
            return {"text": ", ".join(competitors), "selected_options": []}
        return {"text": "", "selected_options": ["自动发现"]}
    optional = inputs.get("clarify_answers", {})
    if isinstance(optional, dict):
        for target in targets:
            answer = optional.get(target)
            if isinstance(answer, str) and answer.strip():
                return {"text": answer.strip(), "selected_options": []}
    return {"text": str(inputs.get("user_query", "")), "selected_options": []}


def _extract_evidence_items(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        items = payload.get("items", [])
        return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []
    return []


def _get_optional(client: httpx.Client, path: str) -> Any:
    response = client.get(path)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    return response.json()


def agent_target(inputs: dict[str, Any]) -> dict[str, Any]:
    """Run one dataset example through the deployed Agent HTTP API."""
    base_url = os.getenv("AGENT_EVAL_BASE_URL", "http://localhost:8090").rstrip("/")
    timeout = _timeout_seconds(inputs)
    payload = {
        "user_query": str(inputs["user_query"]),
        "research_mode": inputs.get("research_mode"),
        "competitors": list(inputs.get("competitors", [])),
        "domain_hint": inputs.get("domain_hint"),
        "report_depth": inputs.get("report_depth", "quick"),
        "response_language": inputs.get("response_language"),
        "focus_dimensions": list(inputs.get("focus_dimensions", [])),
        "reference_urls": list(inputs.get("reference_urls", [])),
    }
    with httpx.Client(
        base_url=base_url,
        headers={**_headers(), "X-Agent-Source": "eval"},
        timeout=30.0,
    ) as client:
        accepted = client.post("/api/runs", json=payload)
        accepted.raise_for_status()
        run_id = accepted.json()["run_id"]
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            detail = client.get(f"/api/runs/{run_id}")
            detail.raise_for_status()
            status = detail.json().get("status")
            if status in {"completed", "degraded", "failed", "cancelled"}:
                break
            time.sleep(2.0)
        else:
            raise TimeoutError(f"Agent run {run_id} did not finish within {timeout:.0f}s")

        report = client.get(f"/api/runs/{run_id}/report")
        report.raise_for_status()
        metrics = client.get(f"/api/runs/{run_id}/metrics")
        metrics.raise_for_status()
        evidence = client.get(f"/api/runs/{run_id}/evidence")
        evidence.raise_for_status()
        evidence_payload = evidence.json()
        if isinstance(evidence_payload, list):
            evidence_items = evidence_payload
        elif isinstance(evidence_payload, dict):
            evidence_items = evidence_payload.get("items", [])
        else:
            evidence_items = []
        return {
            "run_id": run_id,
            "status": status,
            "research_mode": inputs.get("research_mode", "general"),
            "report": report.json(),
            "metrics": metrics.json(),
            "evidence_ids": [item.get("evidence_id") for item in evidence_items if item.get("evidence_id")],
        }


def full_flow_agent_target(inputs: dict[str, Any]) -> dict[str, Any]:
    """Run one example through Intake, plan confirmation, and the production graph."""

    base_url = os.getenv("AGENT_EVAL_BASE_URL", "http://localhost:8090").rstrip("/")
    timeout = _timeout_seconds(inputs)
    headers = {**_headers(), "X-Agent-Source": "eval"}
    competitors = [str(item).strip() for item in inputs.get("competitors", []) if str(item).strip()]
    payload = {
        "user_query": str(inputs["user_query"]),
        "research_mode": inputs.get("research_mode"),
        "user_role": _role_for_mode(inputs),
        "competitors_explicit": competitors,
        "competitors_discovery_mode": not competitors,
        "domain_hint": inputs.get("domain_hint"),
        "report_depth": inputs.get("report_depth", "quick"),
        "response_language": inputs.get("response_language"),
        "focus_dimensions": list(inputs.get("focus_dimensions", [])),
        "reference_urls": list(inputs.get("reference_urls", [])),
    }
    with httpx.Client(base_url=base_url, headers=headers, timeout=30.0) as client:
        accepted = client.post("/api/runs/intake", json=payload)
        accepted.raise_for_status()
        run_id = accepted.json()["run_id"]
        deadline = time.monotonic() + timeout
        replied_at: dict[str, float] = {}
        plan_confirmed = False

        while time.monotonic() < deadline:
            session = _get_optional(client, f"/api/runs/{run_id}/intake-session")
            if isinstance(session, dict) and session.get("awaiting_user"):
                phase = session.get("phase")
                pending = session.get("pending_clarify")
                if phase == "planning" and not isinstance(pending, dict):
                    reply = {"text": "", "selected_options": [str(inputs.get("report_depth", "quick"))]}
                    response = client.post(f"/api/runs/{run_id}/intake/reply", json=reply)
                    if response.status_code not in {200, 409}:
                        response.raise_for_status()
                    time.sleep(0.5)
                    continue
                targets = pending.get("field_targets", []) if isinstance(pending, dict) else []
                targets = [str(target) for target in targets]
                key = ",".join(targets)
                last_sent = replied_at.get(key, 0.0)
                if time.monotonic() - last_sent >= 5.0:
                    reply = _reply_for_targets(inputs, targets)
                    response = client.post(f"/api/runs/{run_id}/intake/reply", json=reply)
                    if response.status_code == 200:
                        replied_at[key] = time.monotonic()
                    elif response.status_code not in {409}:
                        response.raise_for_status()
                time.sleep(0.5)
                continue

            detail = client.get(f"/api/runs/{run_id}")
            detail.raise_for_status()
            detail_payload = detail.json()
            status = detail_payload.get("status")
            if not plan_confirmed and detail_payload.get("plan_tree"):
                response = client.post(
                    f"/api/runs/{run_id}/plan/confirm",
                    json={"disabled_task_ids": [], "additional_tasks": []},
                )
                if response.status_code == 200:
                    plan_confirmed = True
                elif response.status_code not in {409, 422}:
                    response.raise_for_status()
            if status in {"completed", "degraded", "failed", "cancelled"}:
                break
            time.sleep(1.0)
        else:
            raise TimeoutError(f"Agent full-flow run {run_id} did not finish within {timeout:.0f}s")

        detail_payload = client.get(f"/api/runs/{run_id}").json()
        report = _get_optional(client, f"/api/runs/{run_id}/report")
        metrics = _get_optional(client, f"/api/runs/{run_id}/metrics") or {}
        evidence_items = _extract_evidence_items(_get_optional(client, f"/api/runs/{run_id}/evidence"))
        trace = _get_optional(client, f"/api/runs/{run_id}/trace")
        return {
            "run_id": run_id,
            "status": detail_payload.get("status"),
            "detail": detail_payload,
            "research_mode": inputs.get("research_mode", "general"),
            "report": report or {},
            "metrics": metrics,
            "evidence_ids": [item.get("evidence_id") for item in evidence_items if item.get("evidence_id")],
            "trace": trace or {},
            "plan_confirmed": plan_confirmed,
        }

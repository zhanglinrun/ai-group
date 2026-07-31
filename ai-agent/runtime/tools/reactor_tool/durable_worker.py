# -*- coding: utf-8 -*-
"""Internal durable-worker protocol for deterministic Python data-plane tools."""

from __future__ import annotations

import asyncio
import hashlib
import json
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from reactor_tool.tool.code_interpreter import execute_durable_code_request
from reactor_tool.tool.deepsearch import DeepSearch


router = APIRouter(prefix="/internal/runtime/tools", tags=["durable-tools"])
_INVOCATIONS: dict[int, dict[str, Any]] = {}
_LOCK = asyncio.Lock()


class ExecuteCommand(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    invocation_id: int = Field(alias="invocationId")
    run_id: int = Field(alias="runId")
    request_id: str = Field(alias="requestId")
    tool_call_id: str = Field(alias="toolCallId")
    tool_name: str = Field(alias="toolName")
    operation_key: str = Field(alias="operationKey")
    attempt_no: int = Field(alias="attemptNo", ge=1)
    fencing_token: int = Field(alias="fencingToken", ge=1)
    input: dict[str, Any] = Field(default_factory=dict)


class HeartbeatCommand(BaseModel):
    invocation_id: int = Field(alias="invocationId")
    attempt_no: int = Field(alias="attemptNo", ge=1)
    fencing_token: int = Field(alias="fencingToken", ge=1)


class ResultCallback(BaseModel):
    invocation_id: int = Field(alias="invocationId")
    attempt_no: int = Field(alias="attemptNo", ge=1)
    fencing_token: int = Field(alias="fencingToken", ge=1)
    status: str
    result: Any = None
    error_type: str | None = Field(default=None, alias="errorType")
    provider_request_id: str | None = Field(default=None, alias="providerRequestId")


@router.post("/execute")
async def execute(command: ExecuteCommand, request: Request) -> dict[str, Any]:
    _verify_context_headers(request, command.request_id, command.run_id, command.fencing_token)
    async with _LOCK:
        existing = _INVOCATIONS.get(command.invocation_id)
        if existing is not None:
            if existing["fencingToken"] != command.fencing_token:
                raise HTTPException(status_code=409, detail="fencing token rejected")
            return dict(existing, duplicate=True)
        _INVOCATIONS[command.invocation_id] = _new_state(command)

    try:
        if command.tool_name == "deep_search":
            query = str(command.input.get("query") or "").strip()
            max_results = int(command.input.get("maxResults") or command.input.get("limit") or 10)
            result: Any = {
                "evidenceCandidates": await DeepSearch().collect_evidence(query, command.request_id, max_results),
            }
            status, error_type = "SUCCEEDED", None
        elif command.tool_name == "code_interpreter":
            result_response = await execute_durable_code_request(command.input, command.request_id)
            status = str(result_response["status"])
            error_type = result_response.get("errorType")
            result = result_response.get("result")
        else:
            status, error_type, result = "FAILED", "UNSUPPORTED_DURABLE_TOOL", {"toolName": command.tool_name}
        return await _record_result(command.invocation_id, command.attempt_no, command.fencing_token,
                                    status, result, error_type)
    except Exception as error:
        return await _record_result(command.invocation_id, command.attempt_no, command.fencing_token,
                                    "FAILED", {"message": str(error)}, error.__class__.__name__)


@router.get("/{invocation_id}")
async def status(invocation_id: int, request: Request) -> dict[str, Any]:
    state = _INVOCATIONS.get(invocation_id)
    if state is None:
        raise HTTPException(status_code=404, detail="invocation not found")
    _verify_context_headers(request, state["requestId"], state["runId"], state["fencingToken"])
    return state


@router.post("/heartbeat")
async def heartbeat(command: HeartbeatCommand, request: Request) -> dict[str, Any]:
    state = _INVOCATIONS.get(command.invocation_id)
    if state is None:
        raise HTTPException(status_code=404, detail="invocation not found")
    _verify_context_headers(request, state["requestId"], state["runId"], command.fencing_token)
    if state["fencingToken"] != command.fencing_token or state["attemptNo"] != command.attempt_no:
        raise HTTPException(status_code=409, detail="fencing token rejected")
    if state["status"] not in {"SCHEDULED", "RUNNING", "CANCEL_REQUESTED"}:
        return {"accepted": False, "status": state["status"]}
    state["heartbeatAt"] = _now()
    return {"accepted": True, "status": state["status"]}


@router.post("/{invocation_id}/cancel")
async def cancel(invocation_id: int, request: Request) -> dict[str, Any]:
    state = _INVOCATIONS.get(invocation_id)
    if state is None:
        raise HTTPException(status_code=404, detail="invocation not found")
    _verify_context_headers(request, state["requestId"], state["runId"], state["fencingToken"])
    if state["status"] in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "UNKNOWN"}:
        return {"accepted": False, "status": state["status"]}
    state["status"] = "CANCEL_REQUESTED"
    state["heartbeatAt"] = _now()
    return {"accepted": True, "status": state["status"]}


@router.post("/result")
async def result_callback(command: ResultCallback, request: Request) -> dict[str, Any]:
    state = _INVOCATIONS.get(command.invocation_id)
    if state is None:
        raise HTTPException(status_code=404, detail="invocation not found")
    _verify_context_headers(request, state["requestId"], state["runId"], command.fencing_token)
    return await _record_result(command.invocation_id, command.attempt_no, command.fencing_token,
                                command.status, command.result, command.error_type, command.provider_request_id)


async def _record_result(invocation_id: int, attempt_no: int, fencing_token: int, status: str,
                         result: Any, error_type: str | None, provider_request_id: str | None = None) -> dict[str, Any]:
    normalized_status = status.upper()
    if normalized_status not in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "UNKNOWN"}:
        raise HTTPException(status_code=422, detail="result callback requires a terminal status")
    async with _LOCK:
        state = _INVOCATIONS[invocation_id]
        if state["fencingToken"] != fencing_token or state["attemptNo"] != attempt_no:
            raise HTTPException(status_code=409, detail="fencing token rejected")
        if state["status"] in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "UNKNOWN"}:
            return dict(state, duplicate=True)
        state.update({
            "status": normalized_status,
            "result": result,
            "errorType": error_type,
            "providerRequestId": provider_request_id or state["providerRequestId"],
            "resultHash": _hash(result),
            "finishedAt": _now(),
            "heartbeatAt": _now(),
        })
        return dict(state, duplicate=False)


def _new_state(command: ExecuteCommand) -> dict[str, Any]:
    return {
        "invocationId": command.invocation_id,
        "runId": command.run_id,
        "requestId": command.request_id,
        "toolCallId": command.tool_call_id,
        "toolName": command.tool_name,
        "operationKey": command.operation_key,
        "attemptNo": command.attempt_no,
        "fencingToken": command.fencing_token,
        "status": "RUNNING",
        "providerRequestId": "python-worker-" + uuid.uuid4().hex,
        "heartbeatAt": _now(),
    }


def _verify_context_headers(request: Request, request_id: str, run_id: int, fencing_token: int) -> None:
    expected = {
        "X-Request-Id": request_id,
        "X-Agent-Run-Id": str(run_id),
        "X-Fencing-Token": str(fencing_token),
    }
    for header, value in expected.items():
        if request.headers.get(header) != value:
            raise HTTPException(status_code=400, detail=f"missing or mismatched {header}")
    if not request.headers.get("X-Trace-Id"):
        raise HTTPException(status_code=400, detail="missing X-Trace-Id")


def _hash(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()

import os
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from reactor_tool.tool.code_interpreter import execute_code_request
from server import create_app


def _headers():
    return {
        "X-Tool-Token": "test-token",
        "X-Request-Id": "req-durable-1",
        "X-Agent-Run-Id": "91",
        "X-Fencing-Token": "7",
        "X-Trace-Id": "trace-durable-1",
    }


def _command():
    return {
        "invocationId": 9001,
        "runId": 91,
        "requestId": "req-durable-1",
        "toolCallId": "tool-1",
        "toolName": "deep_search",
        "operationKey": "sha256:durable",
        "attemptNo": 1,
        "fencingToken": 7,
        "input": {"query": "durable tools"},
    }


def test_internal_durable_worker_requires_token_and_context_headers(monkeypatch):
    monkeypatch.setenv("REACTOR_TOOL_ENV", "test")
    monkeypatch.setenv("REACTOR_TOOL_TOKEN", "test-token")
    client = TestClient(create_app())

    assert client.post("/internal/runtime/tools/execute", json=_command()).status_code == 401
    response = client.post("/internal/runtime/tools/execute", json=_command(), headers={"X-Tool-Token": "test-token"})
    assert response.status_code == 400
    assert "X-Request-Id" in response.json()["detail"]


def test_duplicate_callback_is_acknowledged_once(monkeypatch):
    monkeypatch.setenv("REACTOR_TOOL_ENV", "test")
    monkeypatch.setenv("REACTOR_TOOL_TOKEN", "test-token")
    client = TestClient(create_app())
    with patch("reactor_tool.durable_worker.DeepSearch.collect_evidence", new=AsyncMock(return_value=[])):
        first = client.post("/internal/runtime/tools/execute", json=_command(), headers=_headers())
    assert first.status_code == 200
    assert first.json()["status"] == "SUCCEEDED"

    duplicate = client.post("/internal/runtime/tools/result", json={
        "invocationId": 9001,
        "attemptNo": 1,
        "fencingToken": 7,
        "status": "SUCCEEDED",
        "result": {"evidenceCandidates": []},
    }, headers=_headers())
    assert duplicate.status_code == 200
    assert duplicate.json()["duplicate"] is True


def test_code_interpreter_durable_result_exposes_uploaded_file_info(monkeypatch):
    monkeypatch.setenv("REACTOR_TOOL_ENV", "test")
    monkeypatch.setenv("REACTOR_TOOL_TOKEN", "test-token")
    client = TestClient(create_app())
    command = _command()
    command["invocationId"] = 9002
    command["toolName"] = "code_interpreter"
    command["input"] = {"code": "print('durable-artifact')", "permissionProfile": "analysis"}
    durable_result = {
        "status": "SUCCEEDED",
        "errorType": None,
        "result": {
            "stdout": "durable-artifact\n",
            "fileInfo": [{
                "fileName": "durable.csv",
                "downloadUrl": "http://files.test/download/durable.csv",
                "domainUrl": "http://files.test/preview/durable.csv",
                "fileSize": 42,
            }],
        },
    }
    with patch("reactor_tool.durable_worker.execute_durable_code_request", new=AsyncMock(return_value=durable_result)):
        response = client.post("/internal/runtime/tools/execute", json=command, headers=_headers())

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "SUCCEEDED"
    assert payload["result"]["fileInfo"][0]["fileName"] == "durable.csv"
    assert payload["result"]["fileInfo"][0]["downloadUrl"]
    assert payload["result"]["fileInfo"][0]["domainUrl"]


def test_code_sandbox_denies_frozen_path_and_sensitive_environment(monkeypatch):
    monkeypatch.setenv("RESEARCHPILOT_SECRET", "never-visible")
    denied = execute_code_request({
        "code": "open('E:/javaproject/ai-group/group/blocked.txt', 'w').write('x')",
        "permissionProfile": "analysis",
    })
    assert denied["status"] == "FAILED"
    assert denied["errorType"] == "path_outside_allowed_roots"

    safe = execute_code_request({"code": "print('sandbox-ok')", "permissionProfile": "analysis"})
    assert safe["status"] == "SUCCEEDED"
    assert "sandbox-ok" in safe["result"]["stdout"]
    assert "never-visible" not in safe["result"]["stdout"]

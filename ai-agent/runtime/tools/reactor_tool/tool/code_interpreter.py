# -*- coding: utf-8 -*-
"""Deterministic, policy-enforced code sandbox used by the durable worker."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import signal
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, AsyncGenerator

from reactor_tool.model.code import ActionOutput
from reactor_tool.tool.code_interpreter_policy import (
    CodeExecutionPermissionError,
    build_permission_policy,
    validate_code_against_policy,
)
from reactor_tool.util.file_util import upload_file_by_path


MAX_OUTPUT_CHARS = 64_000
MAX_TIMEOUT_SECONDS = 120
MAX_MEMORY_BYTES = 512 * 1024 * 1024


class SandboxProcessTimeout(RuntimeError):
    """Timeout with the captured output after the whole sandbox process group is reaped."""

    def __init__(self, stdout: str | None, stderr: str | None):
        super().__init__("sandbox process timed out")
        self.stdout = stdout
        self.stderr = stderr


def execute_code_request(payload: dict[str, Any], retain_artifact_root: bool = False) -> dict[str, Any]:
    """Execute explicit code only; natural-language tasks are never converted into hidden model calls."""
    code = str(payload.get("code") or payload.get("script") or "")
    if not code.strip():
        return {
            "status": "FAILED",
            "errorType": "CODE_REQUIRED",
            "result": {"stdout": "", "stderr": "An explicit code/script field is required."},
        }
    profile = str(payload.get("permissionProfile") or payload.get("permission_profile") or "analysis")
    timeout_seconds = min(max(int(payload.get("timeoutSeconds") or 60), 1), MAX_TIMEOUT_SECONDS)
    work_dir = Path(tempfile.mkdtemp(prefix="researchpilot-ci-"))
    retain_workspace = False
    try:
        if os.name != "nt":
            work_dir.chmod(0o700)
        input_dir = work_dir / "inputs"
        output_dir = work_dir / "output"
        input_dir.mkdir(mode=0o700)
        output_dir.mkdir(mode=0o700)
        input_files = _materialize_inputs(payload.get("inputFiles") or payload.get("input_files"), input_dir)
        policy = build_permission_policy(
            profile=profile,
            workspace_root=str(work_dir),
            output_dir=str(output_dir),
            input_files=input_files,
        )
        validate_code_against_policy(code, policy)
        script_path = work_dir / "task.py"
        script_path.write_text(_bootstrap(policy) + "\n" + code, encoding="utf-8")
        if os.name != "nt":
            script_path.chmod(0o600)
        result = _run_sandbox_process(
            [sys.executable, "-I", str(script_path)],
            cwd=work_dir,
            env=_sandbox_environment(work_dir),
            timeout=timeout_seconds,
        )
        response = {
            "status": "SUCCEEDED" if result.returncode == 0 else "FAILED",
            "errorType": None if result.returncode == 0 else "CODE_EXIT_NON_ZERO",
            "result": {
                "stdout": _truncate(result.stdout),
                "stderr": _truncate(result.stderr),
                "exitCode": result.returncode,
                "artifacts": _artifacts(output_dir),
                "sandboxProfile": policy.profile,
                "sandboxUser": _sandbox_user(),
            },
        }
        # The HTTP/SSE compatibility route must upload artifacts before the
        # sandbox directory is removed. This private hand-off is never exposed
        # in a response; code_interpreter_agent clears it after upload.
        if retain_artifact_root and result.returncode == 0:
            response["result"]["_artifactRoot"] = str(output_dir)
            retain_workspace = True
        return response
    except CodeExecutionPermissionError as error:
        return {"status": "FAILED", "errorType": error.blocked_reason, "result": error.to_public_payload()}
    except SandboxProcessTimeout as error:
        return {
            "status": "TIMED_OUT",
            "errorType": "SANDBOX_TIMEOUT",
            "result": {"stdout": _truncate(error.stdout), "stderr": _truncate(error.stderr)},
        }
    except Exception as error:
        return {"status": "FAILED", "errorType": error.__class__.__name__, "result": {"stderr": str(error)}}
    finally:
        if not retain_workspace:
            shutil.rmtree(work_dir, ignore_errors=True)


async def code_interpreter_agent(
    task: str,
    file_names: list[str] | None = None,
    max_file_abstract_size: int = 2000,
    max_tokens: int = 32000,
    request_id: str = "",
    stream: bool = True,
    permission_profile: str = "analysis",
    code: str | None = None,
) -> AsyncGenerator[ActionOutput, None]:
    """Compatibility adapter for the legacy SSE route, with no planning or model execution."""
    payload = _parse_task(task)
    payload.setdefault("permissionProfile", permission_profile)
    if code and code.strip():
        payload["code"] = code
    response = await asyncio.to_thread(execute_code_request, payload, True)
    result = response.get("result") or {}
    artifact_root = result.pop("_artifactRoot", None) if isinstance(result, dict) else None
    try:
        if response.get("status") == "SUCCEEDED" and artifact_root:
            uploaded, upload_error = await _upload_sandbox_artifacts(
                Path(artifact_root), result.get("artifacts"), request_id
            )
            if upload_error:
                response["status"] = "FAILED"
                response["errorType"] = "ARTIFACT_UPLOAD_FAILED"
                result["artifacts"] = []
                result["stderr"] = _truncate(upload_error)
            else:
                result["artifacts"] = uploaded
        content = json.dumps(response, ensure_ascii=False)
        yield ActionOutput(content=content, file_list=result.get("artifacts", []) if isinstance(result, dict) else [])
    finally:
        _cleanup_retained_artifact_root(artifact_root)


async def execute_durable_code_request(payload: dict[str, Any], request_id: str) -> dict[str, Any]:
    """Run a durable code request and return upload-backed artifact metadata.

    The durable worker cannot hand local sandbox paths to Java: the workspace is
    removed after execution and the Agent ledger needs stable download/preview
    URLs. Keep this contract aligned with the SSE compatibility route while
    preserving the worker's structured stdout/stderr result.
    """
    response = await asyncio.to_thread(execute_code_request, payload, True)
    result = dict(response.get("result") or {})
    artifact_root = result.pop("_artifactRoot", None)
    try:
        if response.get("status") == "SUCCEEDED" and artifact_root:
            uploaded, upload_error = await _upload_sandbox_artifacts(
                Path(artifact_root), result.get("artifacts"), request_id
            )
            if upload_error:
                response["status"] = "FAILED"
                response["errorType"] = "ARTIFACT_UPLOAD_FAILED"
                result["artifacts"] = []
                result["fileInfo"] = []
                result["stderr"] = _truncate(upload_error)
            else:
                # Java's durable result mapper consumes the same fileInfo
                # contract as the streaming code_interpreter route.
                result["fileInfo"] = uploaded
        response["result"] = result
        return response
    finally:
        _cleanup_retained_artifact_root(artifact_root)


async def _upload_sandbox_artifacts(
    artifact_root: Path,
    artifacts: Any,
    request_id: str,
) -> tuple[list[dict[str, Any]], str | None]:
    """Upload only the files declared by the sandbox, retaining integrity metadata."""
    uploaded: list[dict[str, Any]] = []
    try:
        resolved_root = artifact_root.resolve()
        for artifact in artifacts or []:
            if not isinstance(artifact, dict):
                return [], "sandbox returned an invalid artifact declaration"
            relative_path = str(artifact.get("relativePath") or "")
            candidate = (resolved_root / relative_path).resolve()
            if not relative_path or resolved_root not in candidate.parents or not candidate.is_file():
                return [], "sandbox artifact path validation failed"
            file_info = await upload_file_by_path(str(candidate), request_id)
            if not file_info:
                return [], "sandbox artifact upload returned no file metadata"
            file_info["relativePath"] = relative_path.replace("\\", "/")
            if artifact.get("sha256"):
                file_info["sha256"] = artifact["sha256"]
            uploaded.append(file_info)
        return uploaded, None
    except Exception as error:
        return [], f"sandbox artifact upload failed: {error.__class__.__name__}"


def _cleanup_retained_artifact_root(artifact_root: str | None) -> None:
    if not artifact_root:
        return
    root = Path(artifact_root)
    workspace = root.parent
    if workspace.name.startswith("researchpilot-ci-"):
        shutil.rmtree(workspace, ignore_errors=True)


def _parse_task(task: str | None) -> dict[str, Any]:
    normalized = (task or "").strip()
    if normalized.startswith("{"):
        try:
            parsed = json.loads(normalized)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass
    # A direct Python snippet is allowed only when it has unmistakable code syntax.
    if "\n" in normalized or normalized.startswith(("import ", "from ", "print(", "#")):
        return {"code": normalized}
    return {"task": normalized}


def _materialize_inputs(raw_files: Any, input_dir: Path) -> list[dict[str, str]]:
    materialized: list[dict[str, str]] = []
    for index, item in enumerate(raw_files or []):
        if not isinstance(item, dict):
            continue
        name = Path(str(item.get("name") or item.get("fileName") or f"input-{index}")).name
        content = item.get("content")
        source = item.get("path") or item.get("filePath")
        target = input_dir / name
        if content is not None:
            target.write_text(str(content), encoding="utf-8")
        elif source and Path(str(source)).is_file():
            shutil.copyfile(str(source), target)
        else:
            continue
        target.chmod(0o644)
        materialized.append({"name": name, "path": str(target)})
    return materialized


def _bootstrap(policy) -> str:
    input_paths = json.dumps(policy.input_file_paths, ensure_ascii=False)
    output_dir = json.dumps(policy.output_dir, ensure_ascii=False)
    workspace_dir = json.dumps(policy.workspace_root, ensure_ascii=False)
    return f'''from pathlib import Path
_OUTPUT_DIR = Path({output_dir})
_WORKSPACE_DIR = Path({workspace_dir})
_INPUT_FILES = {input_paths}
def build_output_path(name):
    value = (_OUTPUT_DIR / str(name)).resolve()
    if _OUTPUT_DIR not in value.parents and value != _OUTPUT_DIR: raise ValueError("output path denied")
    value.parent.mkdir(parents=True, exist_ok=True)
    return str(value)
def resolve_input_path(name):
    if str(name) not in _INPUT_FILES: raise ValueError("input path denied")
    return _INPUT_FILES[str(name)]
def read_text_file(path, encoding="utf-8"):
    return Path(path).read_text(encoding=encoding)
def write_text_file(path, content, encoding="utf-8"):
    Path(path).write_text(content, encoding=encoding)
'''


def _sandbox_environment(work_dir: Path) -> dict[str, str]:
    return {
        "PATH": os.defpath,
        "HOME": str(work_dir),
        "TMPDIR": str(work_dir),
        "PYTHONIOENCODING": "utf-8",
        "PYTHONNOUSERSITE": "1",
        "LANG": "C.UTF-8",
    }


def _sandbox_preexec() -> None:
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (MAX_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS + 1))
    resource.setrlimit(resource.RLIMIT_AS, (MAX_MEMORY_BYTES, MAX_MEMORY_BYTES))
    if os.geteuid() == 0:
        import pwd
        nobody = pwd.getpwnam("nobody")
        os.setgid(nobody.pw_gid)
        os.setuid(nobody.pw_uid)


def _run_sandbox_process(command: list[str], cwd: Path, env: dict[str, str], timeout: int):
    options: dict[str, Any] = {
        "cwd": cwd,
        "env": env,
        "text": True,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.PIPE,
    }
    if os.name == "posix":
        options["start_new_session"] = True
        options["preexec_fn"] = _sandbox_preexec
    elif os.name == "nt":
        options["creationflags"] = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)

    process = subprocess.Popen(command, **options)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _reap_sandbox_process(process)
        stdout, stderr = process.communicate()
        raise SandboxProcessTimeout(stdout, stderr)
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def _reap_sandbox_process(process: subprocess.Popen) -> None:
    """Terminate the sandbox command and any subprocesses it created after a timeout."""
    if process.poll() is not None:
        return
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGKILL)
            return
        except ProcessLookupError:
            return
    if os.name == "nt":
        try:
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(process.pid)],
                capture_output=True,
                timeout=5,
                check=False,
            )
            return
        except (FileNotFoundError, subprocess.SubprocessError):
            pass
    process.kill()


def _artifacts(output_dir: Path) -> list[dict[str, Any]]:
    artifacts = []
    for path in sorted(output_dir.rglob("*")):
        if not path.is_file():
            continue
        content = path.read_bytes()
        artifacts.append({
            "fileName": path.name,
            "relativePath": str(path.relative_to(output_dir)).replace("\\", "/"),
            "fileSize": len(content),
            "sha256": "sha256:" + hashlib.sha256(content).hexdigest(),
        })
    return artifacts


def _truncate(value: Any) -> str:
    text = "" if value is None else str(value)
    return text if len(text) <= MAX_OUTPUT_CHARS else text[:MAX_OUTPUT_CHARS] + "\n...[output truncated by sandbox policy]"


def _sandbox_user() -> str:
    return str(os.geteuid()) if hasattr(os, "geteuid") else "restricted-process"

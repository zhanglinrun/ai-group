# -*- coding: utf-8 -*-
"""
skill 脚本运行时支持。
"""
import asyncio
import json
import os
import signal
import shutil
import subprocess
import sys
import tempfile
import weakref
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator, Dict, List


SUPPORTED_RUNTIMES = {"python", "node", "shell", "powershell", "bat"}
_RUNTIME_EXTENSIONS = {
    "python": {".py"},
    "node": {".js", ".cjs", ".mjs"},
    "shell": {".sh"},
    "powershell": {".ps1"},
    "bat": {".bat", ".cmd"},
}

# 传递给被执行脚本的环境变量中，凡命中这些片段的键都会被剔除，防止密钥泄漏给不可信代码。
_SECRET_ENV_MARKERS = ("KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD", "PWD",
                       "CREDENTIAL", "PRIVATE", "ACCESSKEY", "ACCESS_ID")
_DEFAULT_CHILD_ENV_ALLOWLIST = {
    "HOME", "LANG", "LC_ALL", "PATH", "PATHEXT", "PYTHONIOENCODING",
    "SYSTEMROOT", "TEMP", "TMP", "TMPDIR", "USERPROFILE", "WINDIR",
}
_RUNTIME_SEMAPHORES: "weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, tuple[int, asyncio.Semaphore]]" = (
    weakref.WeakKeyDictionary()
)


def _sanitize_child_env() -> Dict[str, str]:
    """只传递最小环境变量集合，并二次剔除疑似密钥。"""
    configured_names = {
        item.strip().upper()
        for item in os.getenv("SKILL_CHILD_ENV_ALLOWLIST", "").split(",")
        if item.strip()
    }
    allowed_names = _DEFAULT_CHILD_ENV_ALLOWLIST | configured_names
    sanitized: Dict[str, str] = {}
    for name, value in os.environ.items():
        if name.upper() not in allowed_names:
            continue
        if any(marker in name.upper() for marker in _SECRET_ENV_MARKERS):
            continue
        sanitized[name] = value
    sanitized["PYTHONIOENCODING"] = "utf-8"
    sanitized["PYTHONUNBUFFERED"] = "1"
    return sanitized


@dataclass
class PreparedWorkspace:
    """脚本执行前准备好的隔离工作区。"""

    temp_dir: Path
    skill_root: Path
    arguments_file: Path
    baseline_files: Dict[str, tuple[int, int]]


@dataclass
class RuntimeExecutionResult:
    """运行时执行结果。"""

    success: bool
    exit_code: int
    stdout: str
    stderr: str
    summary: str


def prepare_workspace(skill_base_path: str, arguments: Dict) -> PreparedWorkspace:
    """复制 skill 目录到临时工作区，并写入参数文件。"""
    source_root = Path(skill_base_path).resolve()
    _validate_source_tree(source_root)
    serialized_arguments = json.dumps(arguments or {}, ensure_ascii=False, indent=2)
    if len(serialized_arguments.encode("utf-8")) > _positive_int_env(
        "SKILL_MAX_ARGUMENT_BYTES", 64 * 1024
    ):
        raise ValueError("skill arguments exceed configured byte limit")
    workspace_parent = os.getenv("SKILL_WORKSPACE_ROOT", "").strip()
    workspace_root_dir = None
    if workspace_parent:
        workspace_root_dir = Path(workspace_parent).resolve()
        workspace_root_dir.mkdir(parents=True, exist_ok=True)
    temp_dir = Path(tempfile.mkdtemp(prefix="skill-runner-", dir=workspace_root_dir))
    if os.name != "nt":
        temp_dir.chmod(0o700)
    try:
        workspace_root = temp_dir / source_root.name
        shutil.copytree(source_root, workspace_root)

        internal_dir = workspace_root / ".skill"
        internal_dir.mkdir(parents=True, exist_ok=True)
        arguments_file = internal_dir / "arguments.json"
        arguments_file.write_text(serialized_arguments, encoding="utf-8")

        baseline_files = snapshot_regular_files(workspace_root)
        return PreparedWorkspace(
            temp_dir=temp_dir,
            skill_root=workspace_root,
            arguments_file=arguments_file,
            baseline_files=baseline_files,
        )
    except Exception:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise


def cleanup_workspace(prepared_workspace: PreparedWorkspace | None):
    """清理临时工作区。"""
    if not prepared_workspace:
        return
    shutil.rmtree(prepared_workspace.temp_dir, ignore_errors=True)


def resolve_script_path(skill_base_path: str, script_path: str) -> tuple[Path, Path]:
    """校验脚本路径没有逃逸 skill 根目录。"""
    skill_root = Path(skill_base_path).resolve()
    if not skill_root.is_dir():
        raise ValueError(f"skill base path does not exist: {skill_root}")

    resolved_script = (skill_root / script_path).resolve()
    if skill_root not in {resolved_script, *resolved_script.parents}:
        raise ValueError("script path is outside registered skill directory")
    if not resolved_script.is_file():
        raise ValueError(f"script file does not exist: {resolved_script}")
    return skill_root, resolved_script


def resolve_workspace_script(prepared_workspace: PreparedWorkspace, script_path: str) -> Path:
    """将相对脚本路径映射到临时工作区。"""
    workspace_script = (prepared_workspace.skill_root / script_path).resolve()
    if prepared_workspace.skill_root not in {workspace_script, *workspace_script.parents}:
        raise ValueError("workspace script path is outside isolated skill directory")
    return workspace_script


def snapshot_regular_files(root_path: Path) -> Dict[str, tuple[int, int]]:
    """记录当前工作区的文件快照，用于识别新增产物。"""
    snapshot: Dict[str, tuple[int, int]] = {}
    for file_path in root_path.rglob("*"):
        if file_path.is_symlink():
            raise PermissionError("symbolic links are not allowed in skill workspaces")
        if file_path.is_file():
            relative_path = file_path.relative_to(root_path).as_posix()
            stat_result = file_path.stat()
            snapshot[relative_path] = (int(stat_result.st_size), int(stat_result.st_mtime_ns))
    return snapshot


def collect_generated_files(prepared_workspace: PreparedWorkspace) -> List[Path]:
    """收集执行后新增或变更的文件。"""
    current_snapshot = snapshot_regular_files(prepared_workspace.skill_root)
    generated_files: List[Path] = []
    generated_bytes = 0
    max_generated_files = _positive_int_env("SKILL_MAX_GENERATED_FILES", 20)
    max_generated_bytes = _positive_int_env("SKILL_MAX_GENERATED_BYTES", 100 * 1024 * 1024)
    for relative_path, meta in current_snapshot.items():
        if relative_path.startswith(".skill/"):
            continue
        if prepared_workspace.baseline_files.get(relative_path) != meta:
            generated_files.append(prepared_workspace.skill_root / relative_path)
            generated_bytes += meta[0]
            if len(generated_files) > max_generated_files:
                raise ValueError("generated file count exceeds configured limit")
            if generated_bytes > max_generated_bytes:
                raise ValueError("generated file bytes exceed configured limit")
    return sorted(generated_files, key=lambda item: item.as_posix())


def build_command(runtime: str, script_path: Path, argv: List[str]) -> List[str]:
    """根据 runtime 构造真实命令。"""
    normalized_runtime = (runtime or "").strip().lower()
    if normalized_runtime not in SUPPORTED_RUNTIMES:
        raise ValueError(f"unsupported runtime: {runtime}")
    allowed_runtimes = _allowed_runtimes()
    if normalized_runtime not in allowed_runtimes:
        raise PermissionError(f"runtime denied by policy: {normalized_runtime}")
    allowed_extensions = _RUNTIME_EXTENSIONS[normalized_runtime]
    if script_path.suffix.lower() not in allowed_extensions:
        raise PermissionError(
            f"script extension {script_path.suffix or '<none>'} is not allowed for runtime {normalized_runtime}"
        )
    normalized_argv = [str(item) for item in (argv or [])]
    max_argv_items = _positive_int_env("SKILL_MAX_ARGV_ITEMS", 32)
    max_argv_chars = _positive_int_env("SKILL_MAX_ARGV_CHARS", 8192)
    if len(normalized_argv) > max_argv_items or sum(len(item) for item in normalized_argv) > max_argv_chars:
        raise ValueError("script argv exceeds configured limit")

    if normalized_runtime == "python":
        executable = _resolve_executable(
            "SKILL_PYTHON_BIN",
            [_resolve_project_python(), sys.executable, "python", "python3"],
        )
        return [executable, str(script_path), *normalized_argv]
    if normalized_runtime == "node":
        executable = _resolve_executable("SKILL_NODE_BIN", ["node"])
        return [executable, str(script_path), *normalized_argv]
    if normalized_runtime == "shell":
        executable = _resolve_executable("SKILL_SHELL_BIN", ["bash", "sh"])
        return [executable, str(script_path), *normalized_argv]
    if normalized_runtime == "powershell":
        executable = _resolve_executable("SKILL_POWERSHELL_BIN", ["pwsh", "powershell"])
        return [executable, "-NoProfile", "-NonInteractive", "-File", str(script_path), *normalized_argv]

    executable = _resolve_executable("SKILL_BAT_BIN", [os.environ.get("ComSpec"), "cmd"])
    return [executable, "/d", "/c", str(script_path), *normalized_argv]


async def execute_script(
    runtime: str,
    script_path: Path,
    working_directory: Path,
    arguments: Dict,
    arguments_file: Path,
    argv: List[str],
    timeout_seconds: int,
) -> RuntimeExecutionResult:
    """执行 skill 脚本，并统一返回 stdout / stderr / exit_code。"""
    command = build_command(runtime, script_path, argv or [])
    env = _sanitize_child_env()
    env["SKILL_ARGUMENTS_JSON"] = json.dumps(arguments or {}, ensure_ascii=False)
    env["SKILL_ARGUMENTS_FILE"] = str(arguments_file)
    env["SKILL_WORKSPACE"] = str(working_directory)
    env["SKILL_OUTPUT_DIR"] = str(working_directory / "output")

    configured_timeout = max(1, int(timeout_seconds))
    effective_timeout = min(
        configured_timeout,
        _positive_int_env("SKILL_MAX_TIMEOUT_SECONDS", 120),
    )
    max_output_bytes = _positive_int_env("SKILL_MAX_OUTPUT_BYTES", 1024 * 1024)

    async with _runtime_slot():
        process = await asyncio.create_subprocess_exec(
            *command,
            cwd=str(working_directory),
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            **_subprocess_security_options(effective_timeout),
        )
        collection_task = asyncio.create_task(
            _collect_limited_output(process, max_output_bytes)
        )
        try:
            stdout_bytes, stderr_bytes, stdout_truncated, stderr_truncated = await asyncio.wait_for(
                asyncio.shield(collection_task),
                timeout=effective_timeout,
            )
        except asyncio.TimeoutError:
            await _kill_process(process)
            stdout_bytes, stderr_bytes, stdout_truncated, stderr_truncated = await collection_task
            return RuntimeExecutionResult(
                success=False,
                exit_code=-1,
                stdout=_decode_output(stdout_bytes, stdout_truncated),
                stderr=_merge_output_message(
                    _decode_output(stderr_bytes, stderr_truncated),
                    f"execution timed out after {effective_timeout} seconds",
                ),
                summary="脚本执行超时",
            )

    stdout = _decode_output(stdout_bytes, stdout_truncated)
    stderr = _decode_output(stderr_bytes, stderr_truncated)
    success = process.returncode == 0
    return RuntimeExecutionResult(
        success=success,
        exit_code=process.returncode if process.returncode is not None else -1,
        stdout=stdout,
        stderr=stderr,
        summary="脚本执行成功" if success else "脚本执行失败",
    )


def _resolve_executable(env_key: str, candidates: List[str | None]) -> str:
    """优先取环境变量，其次按候选命令查找运行时。"""
    override = os.getenv(env_key)
    if override:
        return override

    for candidate in candidates:
        if not candidate:
            continue
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
        if Path(candidate).exists():
            return candidate
    raise FileNotFoundError(f"runtime executable not found for {env_key}")


def _resolve_project_python() -> str | None:
    """优先返回 reactor-tool 本地 .venv 的 Python，避免被外层启动器解释器影响。"""
    project_root = Path(__file__).resolve().parents[2]
    windows_python = project_root / ".venv" / "Scripts" / "python.exe"
    unix_python = project_root / ".venv" / "bin" / "python"
    for candidate in (windows_python, unix_python):
        if candidate.exists():
            return str(candidate)
    return None


def _decode_output(content: bytes | None, truncated: bool = False) -> str:
    """优先按 UTF-8 解码输出，失败时降级替换。"""
    if not content:
        decoded = ""
    else:
        try:
            decoded = content.decode("utf-8")
        except UnicodeDecodeError:
            decoded = content.decode("utf-8", errors="replace")
    if truncated:
        return decoded + "\n...[output truncated by reactor-tool policy]"
    return decoded


def _allowed_runtimes() -> set[str]:
    raw_value = os.getenv("SKILL_ALLOWED_RUNTIMES")
    if raw_value is None:
        raw_value = "python"
    configured = {
        item.strip().lower()
        for item in raw_value.split(",")
        if item.strip()
    }
    unknown = configured - SUPPORTED_RUNTIMES
    if unknown:
        raise ValueError(f"unknown runtime in SKILL_ALLOWED_RUNTIMES: {sorted(unknown)}")
    return configured


def _validate_source_tree(source_root: Path):
    if not source_root.is_dir():
        raise ValueError(f"skill base path does not exist: {source_root}")
    max_source_files = _positive_int_env("SKILL_MAX_SOURCE_FILES", 5000)
    max_source_bytes = _positive_int_env("SKILL_MAX_SOURCE_BYTES", 2 * 1024 * 1024 * 1024)
    file_count = 0
    total_bytes = 0
    for path in source_root.rglob("*"):
        if path.is_symlink():
            raise PermissionError("symbolic links are not allowed in skill workspaces")
        if not path.is_file():
            continue
        file_count += 1
        total_bytes += path.stat().st_size
        if file_count > max_source_files:
            raise ValueError("skill source file count exceeds configured limit")
        if total_bytes > max_source_bytes:
            raise ValueError("skill source bytes exceed configured limit")


def _positive_int_env(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None or not raw_value.strip():
        return default
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be greater than zero")
    return value


@asynccontextmanager
async def _runtime_slot() -> AsyncIterator[None]:
    loop = asyncio.get_running_loop()
    limit = _positive_int_env("SKILL_MAX_CONCURRENT_PROCESSES", 2)
    current = _RUNTIME_SEMAPHORES.get(loop)
    if current is None or current[0] != limit:
        current = (limit, asyncio.Semaphore(limit))
        _RUNTIME_SEMAPHORES[loop] = current
    async with current[1]:
        yield


async def _collect_limited_output(
    process: asyncio.subprocess.Process,
    max_output_bytes: int,
) -> tuple[bytes, bytes, bool, bool]:
    stdout_task = asyncio.create_task(_read_stream_limited(process.stdout, max_output_bytes))
    stderr_task = asyncio.create_task(_read_stream_limited(process.stderr, max_output_bytes))
    await process.wait()
    stdout_bytes, stdout_truncated = await stdout_task
    stderr_bytes, stderr_truncated = await stderr_task
    return stdout_bytes, stderr_bytes, stdout_truncated, stderr_truncated


async def _read_stream_limited(
    stream: asyncio.StreamReader | None,
    limit: int,
) -> tuple[bytes, bool]:
    if stream is None:
        return b"", False
    captured = bytearray()
    truncated = False
    while True:
        chunk = await stream.read(64 * 1024)
        if not chunk:
            break
        remaining = limit - len(captured)
        if remaining > 0:
            captured.extend(chunk[:remaining])
        if len(chunk) > max(remaining, 0):
            truncated = True
    return bytes(captured), truncated


def _subprocess_security_options(timeout_seconds: int) -> dict:
    if os.name == "nt":
        return {"creationflags": getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)}
    return {
        "start_new_session": True,
        "preexec_fn": _build_posix_resource_limiter(timeout_seconds),
    }


def _build_posix_resource_limiter(timeout_seconds: int):
    import resource

    cpu_seconds = _positive_int_env("SKILL_MAX_CPU_SECONDS", timeout_seconds + 1)
    memory_mb = _positive_int_env("SKILL_MAX_MEMORY_MB", 2048)
    file_size_mb = _positive_int_env("SKILL_MAX_FILE_SIZE_MB", 100)
    open_files = _positive_int_env("SKILL_MAX_OPEN_FILES", 128)

    def apply_limits():
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
        resource.setrlimit(resource.RLIMIT_AS, (memory_mb * 1024 * 1024, memory_mb * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_FSIZE, (file_size_mb * 1024 * 1024, file_size_mb * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_NOFILE, (open_files, open_files))

    return apply_limits


async def _kill_process(process: asyncio.subprocess.Process):
    if process.returncode is not None:
        return
    if os.name != "nt":
        try:
            os.killpg(process.pid, signal.SIGKILL)
            return
        except ProcessLookupError:
            return
    process.kill()


def _merge_output_message(base_message: str, appended_message: str) -> str:
    if not base_message:
        return appended_message
    return f"{base_message.rstrip()}\n{appended_message}"

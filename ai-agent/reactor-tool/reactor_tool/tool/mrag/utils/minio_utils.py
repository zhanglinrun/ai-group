"""MinIO object-storage helpers for MRAG documents."""

from __future__ import annotations

import hashlib
import os
import pathlib
from datetime import timedelta
from typing import Iterable
from urllib.parse import quote, unquote, urlparse

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from minio import Minio
from minio.error import S3Error

from .logger_utils import logger

router = APIRouter(prefix="/storage", tags=["MinIO"])

LEGACY_BUCKET_NAME = "mrag"
DEFAULT_BUCKET_NAME = "ai-group"
DEFAULT_PUBLIC_BASE_URL = "http://127.0.0.1:1601/v1/storage"


def get_file_extension(file_path: str) -> str:
    extension = pathlib.Path(file_path).suffix
    if "?" in extension:
        extension = extension.split("?", 1)[0]
    return extension


def parse_minio_endpoint(endpoint: str) -> tuple[str, bool]:
    """Return (host:port, secure) for the MinIO SDK."""
    raw = (endpoint or "").strip()
    if not raw:
        raise ValueError("MINIO_ENDPOINT is empty")
    if "://" not in raw:
        raw = f"http://{raw}"
    parsed = urlparse(raw)
    host = parsed.netloc or parsed.path
    if not host:
        raise ValueError(f"invalid MINIO_ENDPOINT: {endpoint}")
    secure = parsed.scheme.lower() == "https"
    return host, secure


def get_minio_settings() -> dict[str, str]:
    endpoint = os.getenv("MINIO_ENDPOINT", "").strip()
    access_key = os.getenv("MINIO_ACCESS_KEY", "").strip()
    secret_key = os.getenv("MINIO_SECRET_KEY", "").strip()
    bucket_name = os.getenv("MINIO_BUCKET_NAME", DEFAULT_BUCKET_NAME).strip() or DEFAULT_BUCKET_NAME
    if not all([endpoint, access_key, secret_key]):
        raise RuntimeError(
            "MinIO is not configured; require MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY"
        )
    return {
        "endpoint": endpoint,
        "access_key": access_key,
        "secret_key": secret_key,
        "bucket_name": bucket_name,
        "public_endpoint": os.getenv("MINIO_PUBLIC_ENDPOINT", "").strip(),
    }


def is_minio_configured() -> bool:
    return all(
        os.getenv(name, "").strip()
        for name in ("MINIO_ENDPOINT", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY")
    )


def create_minio_client(
    endpoint: str | None = None,
    access_key: str | None = None,
    secret_key: str | None = None,
    *,
    public: bool = False,
) -> Minio:
    settings = get_minio_settings()
    chosen_endpoint = endpoint or (
        settings["public_endpoint"] if public and settings["public_endpoint"] else settings["endpoint"]
    )
    host, secure = parse_minio_endpoint(chosen_endpoint)
    return Minio(
        host,
        access_key=access_key or settings["access_key"],
        secret_key=secret_key or settings["secret_key"],
        secure=secure,
    )


def ensure_bucket_ready(client: Minio | None = None, bucket_name: str | None = None) -> str:
    settings = get_minio_settings()
    bucket = bucket_name or settings["bucket_name"]
    minio_client = client or create_minio_client()
    try:
        if not minio_client.bucket_exists(bucket):
            raise RuntimeError(f"MinIO bucket is not available: {bucket}")
    except S3Error as exc:
        raise RuntimeError(f"MinIO bucket readiness check failed for {bucket}: {exc}") from exc
    return bucket


def generate_secure_token(bucket_name: str, object_key: str, secret_key: str | None = None) -> str:
    if not secret_key:
        secret_key = get_minio_settings()["secret_key"]
    data = f"{bucket_name}:{object_key}:{secret_key}"
    return hashlib.sha256(data.encode()).hexdigest()


def verify_token(bucket_name: str, object_key: str, token: str) -> bool:
    expected = generate_secure_token(bucket_name, object_key)
    return token == expected


def create_permanent_download_url(bucket_name: str, object_key: str, secret_key: str | None = None) -> str:
    token = generate_secure_token(bucket_name, object_key, secret_key)
    api_base_url = os.getenv("REACTOR_TOOL_PUBLIC_BASE_URL", DEFAULT_PUBLIC_BASE_URL).rstrip("/")
    return f"{api_base_url}/download/{bucket_name}/{quote(object_key)}/{token}"


def build_object_key(dir_: str, file_name: str) -> str:
    prefix = (dir_ or "").strip().strip("/")
    name = os.path.basename(file_name)
    if not name:
        raise ValueError("object file name is empty")
    return f"{prefix}/{name}" if prefix else name


def upload_minio(
    file_path: str,
    *,
    object_key: str | None = None,
    dir_: str | None = None,
    is_delete: bool = True,
) -> tuple[bool, str | None, str | None, str | None]:
    """
    Upload a local file to the canonical MinIO bucket.

    Returns (success, permanent_url, presigned_url, object_key).
    """
    try:
        settings = get_minio_settings()
        client = create_minio_client()
        bucket_name = ensure_bucket_ready(client)

        if not object_key:
            if dir_ is None:
                raise ValueError("object_key or dir_ is required")
            object_key = build_object_key(dir_, file_path)

        client.fput_object(bucket_name, object_key, file_path)
        permanent_url = create_permanent_download_url(bucket_name, object_key, settings["secret_key"])

        public_client = create_minio_client(public=True)
        presigned_url = public_client.presigned_get_object(
            bucket_name,
            object_key,
            expires=timedelta(days=7),
        )
        return True, permanent_url, presigned_url, object_key
    except Exception as exc:
        logger.error(f"MinIO upload failed: {exc}")
        return False, None, None, None
    finally:
        if is_delete and file_path and os.path.isfile(file_path):
            os.remove(file_path)


def upload_oss(file_path, dir_, is_delete=True, object_key=None):
    """Backward-compatible wrapper used by parsers and routes."""
    success, permanent_url, presigned_url, _ = upload_minio(
        file_path,
        object_key=object_key,
        dir_=dir_,
        is_delete=is_delete,
    )
    if success:
        return True, permanent_url, presigned_url
    return False, None, None


def allowed_download_buckets() -> set[str]:
    buckets = {DEFAULT_BUCKET_NAME, LEGACY_BUCKET_NAME}
    try:
        buckets.add(get_minio_settings()["bucket_name"])
    except RuntimeError:
        pass
    return buckets


@router.get("/download/{bucket_name}/{object_key:path}/{token}")
def download_file(bucket_name: str, object_key: str, token: str):
    """Proxy MinIO object download via a stable reactor-tool URL."""
    try:
        object_key = unquote(object_key)
        if bucket_name not in allowed_download_buckets():
            raise HTTPException(status_code=404, detail="未知存储桶")

        if not verify_token(bucket_name, object_key, token):
            logger.warning(f"无效的访问令牌用于 {bucket_name}/{object_key}")
            raise HTTPException(status_code=403, detail="无效的访问令牌")

        client = create_minio_client()
        try:
            client.stat_object(bucket_name, object_key)
        except S3Error as exc:
            logger.error(f"对象不存在或无法访问: {bucket_name}/{object_key}, 错误: {exc}")
            raise HTTPException(status_code=404, detail="文件不存在或无法访问") from exc

        response = client.get_object(bucket_name, object_key)
        content_type = response.headers.get("Content-Type", "application/octet-stream")
        filename = os.path.basename(object_key)

        def iterfile() -> Iterable[bytes]:
            try:
                yield from response.stream(32 * 1024)
            finally:
                response.close()
                response.release_conn()

        return StreamingResponse(
            iterfile(),
            media_type=content_type,
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )
    except HTTPException:
        raise
    except Exception as exc:
        logger.error(f"下载文件时发生错误: {exc}")
        raise HTTPException(status_code=500, detail="服务器内部错误") from exc

import asyncio
import mimetypes
import os
import threading
from functools import lru_cache
from pathlib import Path
from urllib.parse import quote, urlsplit

from minio import Minio


class MinioObjectStorage:
    """MinIO file storage used by the runtime file service."""

    def __init__(self, endpoint: str, access_key: str, secret_key: str, bucket: str):
        client_endpoint, secure = _parse_endpoint(endpoint)
        self._endpoint = endpoint.rstrip("/")
        self._client = Minio(
            client_endpoint,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
        )
        self.bucket = bucket
        self._bucket_ready = False
        self._bucket_lock = threading.Lock()

    async def ensure_bucket(self) -> None:
        await asyncio.to_thread(self._ensure_bucket_sync)

    async def upload_file(self, local_path: str | Path, object_name: str) -> str:
        path = Path(local_path)
        object_name = _validate_object_name(object_name)
        await self.ensure_bucket()
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        await asyncio.to_thread(
            self._client.fput_object,
            self.bucket,
            object_name,
            str(path),
            content_type=content_type,
        )
        return f"{self._endpoint}/{self.bucket}/{quote(object_name, safe='/')}"

    async def download_file(self, local_path: str | Path, object_name: str) -> None:
        path = Path(local_path)
        object_name = _validate_object_name(object_name)
        path.parent.mkdir(parents=True, exist_ok=True)
        await self.ensure_bucket()
        await asyncio.to_thread(
            self._client.fget_object,
            self.bucket,
            object_name,
            str(path),
        )

    async def download_bytes(self, object_name: str) -> bytes:
        object_name = _validate_object_name(object_name)
        await self.ensure_bucket()
        return await asyncio.to_thread(self._download_bytes_sync, object_name)

    async def delete_file(self, object_name: str) -> None:
        object_name = _validate_object_name(object_name)
        await self.ensure_bucket()
        await asyncio.to_thread(self._client.remove_object, self.bucket, object_name)

    async def restore_file(self, local_path: str | Path, object_name: str) -> None:
        await self.download_file(local_path, object_name)

    def _download_bytes_sync(self, object_name: str) -> bytes:
        response = self._client.get_object(self.bucket, object_name)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    def _ensure_bucket_sync(self) -> None:
        if self._bucket_ready:
            return
        with self._bucket_lock:
            if self._bucket_ready:
                return
            if not self._client.bucket_exists(self.bucket):
                self._client.make_bucket(self.bucket)
            self._bucket_ready = True


def get_minio_storage() -> MinioObjectStorage | None:
    endpoint = os.getenv("MINIO_ENDPOINT", "").strip()
    access_key = os.getenv("MINIO_ACCESS_KEY", "").strip()
    secret_key = os.getenv("MINIO_SECRET_KEY", "").strip()
    if bool(access_key) != bool(secret_key):
        raise RuntimeError("MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be configured together")
    access_key = access_key or os.getenv("MINIO_ROOT_USER", "").strip()
    secret_key = secret_key or os.getenv("MINIO_ROOT_PASSWORD", "").strip()
    bucket = (os.getenv("MINIO_BUCKET_NAME") or "ai-group-files").strip()
    if not endpoint and not access_key and not secret_key:
        return None
    if not endpoint or not access_key or not secret_key:
        raise RuntimeError("MINIO_ENDPOINT and MinIO credentials must be configured together")
    if not bucket:
        raise RuntimeError("MINIO_BUCKET_NAME must not be empty")
    return _build_storage(endpoint, access_key, secret_key, bucket)


@lru_cache(maxsize=8)
def _build_storage(endpoint: str, access_key: str, secret_key: str, bucket: str) -> MinioObjectStorage:
    return MinioObjectStorage(endpoint, access_key, secret_key, bucket)


def _parse_endpoint(endpoint: str) -> tuple[str, bool]:
    raw = endpoint.strip()
    parsed = urlsplit(raw if "://" in raw else f"http://{raw}")
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("MINIO_ENDPOINT must be an HTTP(S) endpoint")
    if parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("MINIO_ENDPOINT must not contain credentials, paths, queries, or fragments")
    return parsed.netloc, parsed.scheme == "https"


def _validate_object_name(object_name: str) -> str:
    normalized = (object_name or "").strip().lstrip("/")
    if not normalized or "\\" in normalized or any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise ValueError("invalid MinIO object name")
    return normalized

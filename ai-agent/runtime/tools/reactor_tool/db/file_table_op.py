import os
from pathlib import Path
from typing import List

from fastapi import UploadFile
from sqlmodel import select

from reactor_tool.db.db_engine import async_session_local
from reactor_tool.db.file_table import FileInfo
from reactor_tool.util.log_util import timer
from reactor_tool.util.minio_storage import get_minio_storage


FILE_PROCESSING = 0
FILE_SUCCESS = 1
FILE_FAILED = -1


class _FileDB:
    def __init__(self):
        self._work_dir = os.getenv("FILE_SAVE_PATH", "file_db_dir")
        Path(self._work_dir).mkdir(parents=True, exist_ok=True)

    async def save(self, file_name: str, content: str, scope: str, object_name: str) -> str:
        file_path = self.path_for(file_name, scope)
        file_path.write_text(content, encoding="utf-8")
        await self._upload_to_object_storage(file_path, object_name)
        return str(file_path)

    async def save_by_data(
        self,
        file: UploadFile,
        scope: str | None,
        object_name: str,
        max_size_bytes: int,
    ) -> str:
        file_path = self.path_for(file.filename, scope)
        size = 0
        try:
            with file_path.open("wb") as target:
                while chunk := await file.read(1024 * 1024):
                    size += len(chunk)
                    if size > max_size_bytes:
                        raise ValueError(f"file exceeds configured limit of {max_size_bytes} bytes")
                    target.write(chunk)
            await self._upload_to_object_storage(file_path, object_name)
            return str(file_path)
        except Exception:
            if size > max_size_bytes:
                file_path.unlink(missing_ok=True)
            raise

    async def ensure_local(self, file_info: FileInfo) -> bool:
        file_path = Path(file_info.file_path)
        if file_path.is_file():
            return True
        storage = get_minio_storage()
        if storage is None:
            return False
        await storage.download_file(file_path, generate_object_name(file_info.file_id, file_info.filename))
        return file_path.is_file()

    async def download_bytes(self, file_info: FileInfo) -> bytes:
        storage = get_minio_storage()
        if storage is not None:
            return await storage.download_bytes(generate_object_name(file_info.file_id, file_info.filename))
        if not await self.ensure_local(file_info):
            raise FileNotFoundError(file_info.file_path)
        return Path(file_info.file_path).read_bytes()

    async def delete(self, file_info: FileInfo) -> None:
        storage = get_minio_storage()
        if storage is not None:
            await storage.delete_file(generate_object_name(file_info.file_id, file_info.filename))
        Path(file_info.file_path).unlink(missing_ok=True)

    def path_for(self, file_name: str, scope: str | None) -> Path:
        safe_scope = "".join(c if c not in '<>:"/\\|?*' else "_" for c in str(scope or ""))
        directory = Path(self._work_dir) / safe_scope if safe_scope else Path(self._work_dir)
        directory.mkdir(parents=True, exist_ok=True)
        return directory / normalize_stored_file_name(file_name)

    async def _upload_to_object_storage(self, file_path: str | Path, object_name: str) -> None:
        storage = get_minio_storage()
        if storage is not None:
            await storage.upload_file(file_path, object_name)


FileDB = _FileDB()


def normalize_stored_file_name(file_name: str) -> str:
    """Normalize externally supplied names before they reach local or object storage."""
    normalized = os.path.basename((file_name or "").strip())
    if not normalized or normalized in {".", ".."}:
        raise ValueError("file_name is empty")
    return normalized


def generate_object_name(file_id: str, file_name: str) -> str:
    suffix = Path(file_name).suffix.lower()
    return f"file-{file_id.replace('-', '')}{suffix}"


def max_file_size_bytes() -> int:
    max_size_mb = int(os.getenv("FILE_MAX_SIZE_MB", "100"))
    if max_size_mb <= 0:
        raise ValueError("FILE_MAX_SIZE_MB must be positive")
    return max_size_mb * 1024 * 1024


class FileInfoOp:
    @classmethod
    @timer()
    async def add_by_content(
        cls,
        filename: str,
        content: str,
        file_id: str,
        description: str | None = None,
        request_id: str | None = None,
    ) -> FileInfo:
        filename = normalize_stored_file_name(filename)
        if "." not in filename:
            filename = f"{filename}.txt"
        content_size = len(content.encode("utf-8"))
        if content_size > max_file_size_bytes():
            raise ValueError("file exceeds configured size limit")
        file_info = FileInfo(
            file_id=file_id,
            filename=filename,
            file_path=str(FileDB.path_for(filename, request_id)),
            description=description,
            file_size=content_size,
            status=FILE_PROCESSING,
            request_id=request_id,
        )
        await cls.add(file_info)
        try:
            file_info.file_path = await FileDB.save(
                filename,
                content,
                scope=request_id,
                object_name=generate_object_name(file_id, filename),
            )
            file_info.file_size = os.path.getsize(file_info.file_path)
            file_info.status = FILE_SUCCESS
        except Exception:
            file_info.status = FILE_FAILED
            await cls.add(file_info)
            raise
        return await cls.add(file_info)

    @staticmethod
    @timer()
    async def add_by_file(file: UploadFile, file_id: str, request_id: str | None = None) -> FileInfo:
        file.filename = normalize_stored_file_name(file.filename)
        file_info = FileInfo(
            file_id=file_id,
            filename=file.filename,
            file_path=str(FileDB.path_for(file.filename, request_id)),
            description="",
            file_size=file.size or 0,
            status=FILE_PROCESSING,
            request_id=request_id,
        )
        await FileInfoOp.add(file_info)
        try:
            file_info.file_path = await FileDB.save_by_data(
                file,
                scope=request_id,
                object_name=generate_object_name(file_id, file.filename),
                max_size_bytes=max_file_size_bytes(),
            )
            file_info.file_size = os.path.getsize(file_info.file_path)
            file_info.status = FILE_SUCCESS
        except Exception:
            file_info.status = FILE_FAILED
            if os.path.isfile(file_info.file_path):
                file_info.file_size = os.path.getsize(file_info.file_path)
            await FileInfoOp.add(file_info)
            raise
        return await FileInfoOp.add(file_info)

    @staticmethod
    @timer()
    async def add(file_info: FileInfo) -> FileInfo:
        async with async_session_local() as session:
            result = await session.execute(select(FileInfo).where(FileInfo.file_id == file_info.file_id))
            stored = result.scalars().one_or_none()
            if stored is None:
                stored = file_info
            else:
                stored.filename = file_info.filename
                stored.file_path = file_info.file_path
                stored.description = file_info.description
                stored.file_size = file_info.file_size
                stored.status = file_info.status
                stored.request_id = file_info.request_id
            session.add(stored)
            await session.commit()
            await session.refresh(stored)
            return stored

    @staticmethod
    @timer()
    async def get_by_file_id(file_id: str) -> FileInfo | None:
        async with async_session_local() as session:
            result = await session.execute(select(FileInfo).where(FileInfo.file_id == file_id))
            return result.scalars().one_or_none()

    @staticmethod
    @timer()
    async def get_by_file_ids(file_ids: List[str]) -> List[FileInfo]:
        async with async_session_local() as session:
            result = await session.execute(select(FileInfo).where(FileInfo.file_id.in_(file_ids)))
            return list(result.scalars().all())

    @staticmethod
    @timer()
    async def get_by_request_id(request_id: str) -> List[FileInfo]:
        async with async_session_local() as session:
            statement = select(FileInfo).where(
                FileInfo.request_id == request_id,
                FileInfo.status == FILE_SUCCESS,
            ).order_by(FileInfo.create_time)
            result = await session.execute(statement)
            return list(result.scalars().all())

    @staticmethod
    @timer()
    async def delete(file_id: str) -> None:
        async with async_session_local() as session:
            result = await session.execute(select(FileInfo).where(FileInfo.file_id == file_id))
            file_info = result.scalars().one_or_none()
            if file_info is None:
                raise FileNotFoundError(file_id)
            await FileDB.delete(file_info)
            await session.delete(file_info)
            await session.commit()


def get_file_preview_url(file_id: str, file_name: str) -> str:
    normalized_file_name = normalize_stored_file_name(file_name)
    return f"{os.getenv('FILE_SERVER_URL')}/preview/{file_id}/{normalized_file_name}"


def get_file_download_url(file_id: str, file_name: str) -> str:
    normalized_file_name = normalize_stored_file_name(file_name)
    return f"{os.getenv('FILE_SERVER_URL')}/download/{file_id}/{normalized_file_name}"

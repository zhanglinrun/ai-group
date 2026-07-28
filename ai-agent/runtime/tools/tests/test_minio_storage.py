import asyncio
import io
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from starlette.datastructures import UploadFile

from reactor_tool.db.file_table_op import FileDB, generate_object_name
from reactor_tool.util.minio_storage import _build_storage, _parse_endpoint, get_minio_storage


@pytest.fixture(autouse=True)
def clean_minio_env(monkeypatch):
    for name in (
        "MINIO_ENDPOINT",
        "MINIO_ACCESS_KEY",
        "MINIO_SECRET_KEY",
        "MINIO_ROOT_USER",
        "MINIO_ROOT_PASSWORD",
        "MINIO_BUCKET_NAME",
    ):
        monkeypatch.delenv(name, raising=False)
    _build_storage.cache_clear()
    yield
    _build_storage.cache_clear()


def test_minio_configuration_is_optional_but_partial_configuration_fails(monkeypatch):
    assert get_minio_storage() is None
    monkeypatch.setenv("MINIO_ENDPOINT", "http://127.0.0.1:9000")
    with pytest.raises(RuntimeError, match="configured together"):
        get_minio_storage()
    monkeypatch.setenv("MINIO_ROOT_USER", "agent")
    monkeypatch.setenv("MINIO_ROOT_PASSWORD", "root-secret")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "dedicated")
    with pytest.raises(RuntimeError, match="ACCESS_KEY and MINIO_SECRET_KEY"):
        get_minio_storage()


def test_parse_minio_endpoint_rejects_embedded_paths():
    assert _parse_endpoint("127.0.0.1:9000") == ("127.0.0.1:9000", False)
    assert _parse_endpoint("https://minio.example.com") == ("minio.example.com", True)
    with pytest.raises(ValueError, match="must not contain"):
        _parse_endpoint("https://minio.example.com/private")


def test_file_db_writes_through_and_restores_from_minio(monkeypatch, tmp_path):
    monkeypatch.setenv("MINIO_ENDPOINT", "http://127.0.0.1:9000")
    monkeypatch.setenv("MINIO_ROOT_USER", "agent")
    monkeypatch.setenv("MINIO_ROOT_PASSWORD", "test-secret")
    monkeypatch.setenv("MINIO_BUCKET_NAME", "agent-files")

    client = MagicMock()
    client.bucket_exists.return_value = True
    client.fget_object.side_effect = lambda _bucket, _key, target: Path(target).write_bytes(b"payload")
    original_work_dir = FileDB._work_dir
    FileDB._work_dir = str(tmp_path)
    upload = UploadFile(filename="poster.png", file=io.BytesIO(b"payload"))

    try:
        with patch("reactor_tool.util.minio_storage.Minio", return_value=client):
            object_name = generate_object_name("file-id-001", "poster.png")
            local_path = asyncio.run(
                FileDB.save_by_data(
                    upload,
                    scope="session-1",
                    object_name=object_name,
                    max_size_bytes=1024,
                )
            )
            client.fput_object.assert_called_once_with(
                "agent-files",
                "file-fileid001.png",
                local_path,
                content_type="image/png",
            )

            Path(local_path).unlink()
            restored = asyncio.run(
                FileDB.ensure_local(
                    SimpleNamespace(file_path=local_path, file_id="file-id-001", filename="poster.png")
                )
            )

        assert restored is True
        assert Path(local_path).read_bytes() == b"payload"
        client.fget_object.assert_called_once_with(
            "agent-files",
            "file-fileid001.png",
            local_path,
        )
    finally:
        FileDB._work_dir = original_work_dir


def test_minio_storage_supports_download_bytes_and_delete(monkeypatch):
    monkeypatch.setenv("MINIO_ENDPOINT", "http://127.0.0.1:9000")
    monkeypatch.setenv("MINIO_ROOT_USER", "agent")
    monkeypatch.setenv("MINIO_ROOT_PASSWORD", "test-secret")
    response = MagicMock()
    response.read.return_value = b"stored-content"
    client = MagicMock()
    client.bucket_exists.return_value = False
    client.get_object.return_value = response

    with patch("reactor_tool.util.minio_storage.Minio", return_value=client):
        storage = get_minio_storage()
        content = asyncio.run(storage.download_bytes("file-demo.txt"))
        asyncio.run(storage.delete_file("file-demo.txt"))

    assert content == b"stored-content"
    client.make_bucket.assert_called_once_with("ai-group-files")
    client.get_object.assert_called_once_with("ai-group-files", "file-demo.txt")
    response.close.assert_called_once()
    response.release_conn.assert_called_once()
    client.remove_object.assert_called_once_with("ai-group-files", "file-demo.txt")


def test_file_db_rejects_oversized_upload(tmp_path):
    original_work_dir = FileDB._work_dir
    FileDB._work_dir = str(tmp_path)
    upload = UploadFile(filename="large.bin", file=io.BytesIO(b"12345"))
    try:
        with pytest.raises(ValueError, match="exceeds configured limit"):
            asyncio.run(
                FileDB.save_by_data(
                    upload,
                    scope="session-1",
                    object_name="file-large.bin",
                    max_size_bytes=4,
                )
            )
        assert not (tmp_path / "session-1" / "large.bin").exists()
    finally:
        FileDB._work_dir = original_work_dir

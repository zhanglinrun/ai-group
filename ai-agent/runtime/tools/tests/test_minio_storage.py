import os
from unittest.mock import MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from reactor_tool.tool.mrag.utils import minio_utils


@pytest.fixture(autouse=True)
def minio_env(monkeypatch):
    monkeypatch.setenv("MINIO_ENDPOINT", "http://127.0.0.1:9000")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "minioadmin")
    monkeypatch.setenv("MINIO_SECRET_KEY", "minioadmin")
    monkeypatch.setenv("MINIO_BUCKET_NAME", "ai-group")
    monkeypatch.setenv("REACTOR_TOOL_PUBLIC_BASE_URL", "http://127.0.0.1:1601/v1/storage")
    monkeypatch.delenv("MINIO_PUBLIC_ENDPOINT", raising=False)


def test_parse_minio_endpoint_http_and_https():
    assert minio_utils.parse_minio_endpoint("http://127.0.0.1:9000") == ("127.0.0.1:9000", False)
    assert minio_utils.parse_minio_endpoint("https://minio.example.com") == ("minio.example.com", True)
    assert minio_utils.parse_minio_endpoint("127.0.0.1:9000") == ("127.0.0.1:9000", False)


def test_ensure_bucket_ready_requires_existing_bucket():
    client = MagicMock()
    client.bucket_exists.return_value = False
    with pytest.raises(RuntimeError, match="not available"):
        minio_utils.ensure_bucket_ready(client, "ai-group")


def test_upload_minio_uses_explicit_object_key_and_presign(tmp_path):
    local_file = tmp_path / "demo.pdf"
    local_file.write_bytes(b"%PDF-1.4")

    client = MagicMock()
    client.bucket_exists.return_value = True
    public_client = MagicMock()
    public_client.presigned_get_object.return_value = "http://minio/presigned"

    with (
        patch("reactor_tool.tool.mrag.utils.minio_utils.create_minio_client", side_effect=[client, public_client]),
        patch("reactor_tool.tool.mrag.utils.minio_utils.ensure_bucket_ready", return_value="ai-group"),
    ):
        ok, permanent, presigned, object_key = minio_utils.upload_minio(
            str(local_file),
            object_key="documents/2026/07/12/doc-1/demo.pdf",
            is_delete=False,
        )

    assert ok is True
    assert object_key == "documents/2026/07/12/doc-1/demo.pdf"
    assert permanent.startswith("http://127.0.0.1:1601/v1/storage/download/ai-group/")
    assert "/documents/2026/07/12/doc-1/demo.pdf/" in permanent
    assert permanent.endswith(minio_utils.generate_secure_token("ai-group", object_key))
    assert presigned == "http://minio/presigned"
    client.fput_object.assert_called_once_with(
        "ai-group",
        "documents/2026/07/12/doc-1/demo.pdf",
        str(local_file),
    )


def test_download_proxy_rejects_bad_token_and_closes_stream():
    app = FastAPI()
    app.include_router(minio_utils.router, prefix="/v1")
    client = TestClient(app)

    object_key = "documents/demo.pdf"
    bad = client.get(f"/v1/storage/download/ai-group/{object_key}/bad-token")
    assert bad.status_code == 403

    token = minio_utils.generate_secure_token("ai-group", object_key)
    mock_client = MagicMock()
    mock_client.stat_object.return_value = MagicMock()
    response_body = MagicMock()
    response_body.headers = {"Content-Type": "application/pdf"}
    response_body.stream.return_value = iter([b"pdf-bytes"])
    mock_client.get_object.return_value = response_body

    with patch("reactor_tool.tool.mrag.utils.minio_utils.create_minio_client", return_value=mock_client):
        ok = client.get(f"/v1/storage/download/ai-group/{object_key}/{token}")

    assert ok.status_code == 200
    assert ok.content == b"pdf-bytes"
    response_body.close.assert_called_once()
    response_body.release_conn.assert_called_once()


def test_upload_document_returns_minio_metadata(monkeypatch):
    from reactor_tool.tool.mrag.api.routes import document as document_routes

    def fake_upload(file_path, object_key=None, dir_=None, is_delete=True):
        return (
            True,
            "http://127.0.0.1:1601/v1/storage/download/ai-group/documents/x/demo.pdf/token",
            "http://minio/presigned",
            object_key,
        )

    monkeypatch.setattr(document_routes, "is_minio_configured", lambda: True)
    monkeypatch.setattr(document_routes, "upload_minio", fake_upload)

    app = FastAPI()
    app.include_router(document_routes.router)
    client = TestClient(app)
    response = client.post(
        "/documents/upload",
        files={"file": ("demo.pdf", b"%PDF-1.4 demo", "application/pdf")},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["data"]["storage_type"] == "minio"
    assert payload["data"]["object_key"].endswith("/demo.pdf")
    assert payload["data"]["object_key"].startswith("documents/")
    assert payload["data"]["oss_path"] == payload["data"]["object_key"]

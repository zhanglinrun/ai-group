"""Local file-service helpers (chat attachments / generated files)."""

from __future__ import annotations

import mimetypes
import os
import uuid

import requests


def upload_local_storage(file_path: str, file_id: str | None = None) -> str:
    if file_id is None:
        file_id = uuid.uuid4().hex

    def get_content_type(path: str) -> str:
        content_type, _ = mimetypes.guess_type(path)
        if content_type is not None:
            return content_type
        ext = os.path.splitext(path)[1].lower()
        content_type_map = {
            ".pdf": "application/pdf",
            ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".doc": "application/msword",
            ".txt": "text/plain",
            ".md": "text/markdown",
            ".json": "application/json",
            ".xml": "application/xml",
            ".csv": "text/csv",
            ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ".xls": "application/vnd.ms-excel",
            ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ".ppt": "application/vnd.ms-powerpoint",
            ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg",
            ".png": "image/png",
            ".gif": "image/gif",
            ".bmp": "image/bmp",
            ".webp": "image/webp",
            ".svg": "image/svg+xml",
        }
        return content_type_map.get(ext, "application/octet-stream")

    file_name = f"{file_id}_{os.path.basename(file_path)}"
    content_type = get_content_type(file_path)
    file_server_url = os.getenv(
        "FILE_SERVER_URL",
        "http://127.0.0.1:1601/v1/file_tool",
    ).rstrip("/")

    with open(file_path, "rb") as handle:
        response = requests.post(
            f"{file_server_url}/upload_file_data",
            files={"file": (file_name, handle, content_type)},
            data={"requestId": uuid.uuid4().hex},
            timeout=60,
        )
    if response.status_code != 200:
        raise Exception(f"Failed to upload file: {response.text}")
    return response.json()["downloadUrl"]

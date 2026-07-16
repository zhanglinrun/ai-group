import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

from reactor_tool.util.file_util import upload_file


def test_upload_file_forwards_internal_tool_token(monkeypatch):
    monkeypatch.setenv("FILE_SERVER_URL", "http://127.0.0.1:1601/v1/file_tool")
    monkeypatch.setenv("REACTOR_TOOL_TOKEN", "expected-token")

    response = MagicMock()
    response.__aenter__ = AsyncMock(return_value=response)
    response.__aexit__ = AsyncMock(return_value=None)
    response.text = AsyncMock(return_value=json.dumps({
        "downloadUrl": "http://127.0.0.1/download/report.html",
        "domainUrl": "http://127.0.0.1/preview/report.html",
    }))
    session = MagicMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=None)
    session.post.return_value = response

    with patch("reactor_tool.util.file_util.aiohttp.ClientSession", return_value=session):
        asyncio.run(upload_file("<html></html>", "report", "html", "request-1"))

    assert session.post.call_args.kwargs["headers"] == {"X-Tool-Token": "expected-token"}

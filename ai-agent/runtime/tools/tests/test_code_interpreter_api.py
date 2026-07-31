# -*- coding: utf-8 -*-
import asyncio
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from reactor_tool.api.tool import router
from reactor_tool.model.code import ActionOutput
from reactor_tool.tool.code_interpreter import code_interpreter_agent


class CodeInterpreterApiTest(unittest.TestCase):

    def test_should_upload_sandbox_artifact_before_workspace_cleanup(self):
        with tempfile.TemporaryDirectory() as storage_root, patch.dict(
            os.environ, {"FILE_SERVER_URL": storage_root}, clear=False
        ):
            async def collect():
                return [chunk async for chunk in code_interpreter_agent(
                    task='{"code":"write_text_file(build_output_path(\\\"artifact.csv\\\"), \\\"a,b\\\\n1,2\\\\n\\\")"}',
                    request_id="code-artifact-test",
                    permission_profile="analysis",
                )]

            chunks = asyncio.run(collect())

            self.assertEqual(1, len(chunks))
            self.assertEqual(1, len(chunks[0].file_list))
            file_info = chunks[0].file_list[0]
            self.assertEqual("artifact.csv", file_info["fileName"])
            self.assertTrue(file_info["ossUrl"])
            self.assertTrue(Path(file_info["ossUrl"]).is_file())
    def test_should_forward_permission_profile_to_code_interpreter_agent(self):
        app = FastAPI()
        app.include_router(router)
        captured_kwargs = {}

        async def fake_agent(**kwargs):
            captured_kwargs.update(kwargs)
            yield ActionOutput(content="ok", file_list=[])

        with patch("reactor_tool.tool.code_interpreter.code_interpreter_agent", new=fake_agent), patch(
            "reactor_tool.api.tool.upload_file",
            new=AsyncMock(return_value={"fileName": "code_output.md"}),
        ):
            client = TestClient(app)
            response = client.post(
                "/code_interpreter",
                json={
                    "requestId": "req-api-1",
                    "task": "生成一个汇总文件",
                    "permissionProfile": "workspace",
                    "stream": False,
                },
            )

        self.assertEqual(200, response.status_code)
        self.assertEqual("workspace", captured_kwargs.get("permission_profile"))


if __name__ == "__main__":
    unittest.main()

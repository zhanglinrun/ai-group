# -*- coding: utf-8 -*-
import os
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from reactor_tool.security import (
    InternalToolTokenMiddleware,
    ReactorToolSecuritySettings,
    load_security_settings,
    validate_bind_address,
)
from server import create_app


class ReactorToolServerSecurityTest(unittest.TestCase):
    def test_non_local_environment_should_fail_closed_without_token(self):
        with patch.dict(
            os.environ,
            {"REACTOR_TOOL_ENV": "prod", "REACTOR_TOOL_TOKEN": "", "AI_GROUP_INTERNAL_TOKEN": ""},
            clear=False,
        ):
            with self.assertRaisesRegex(RuntimeError, "is required outside local development"):
                create_app()

    def test_non_loopback_bind_should_require_token_even_in_local_mode(self):
        with patch.dict(
            os.environ,
            {"REACTOR_TOOL_ENV": "local", "REACTOR_TOOL_TOKEN": "", "AI_GROUP_INTERNAL_TOKEN": ""},
            clear=False,
        ):
            settings = load_security_settings()
            validate_bind_address("127.0.0.1", settings)
            with self.assertRaisesRegex(RuntimeError, "required when binding beyond loopback"):
                validate_bind_address("0.0.0.0", settings)

    def test_tool_api_should_reject_missing_and_wrong_tokens(self):
        with patch.dict(
            os.environ,
            {"REACTOR_TOOL_ENV": "test", "REACTOR_TOOL_TOKEN": "expected-token"},
            clear=False,
        ):
            client = TestClient(create_app())
            missing = client.post("/v1/tool/script_runner", json={})
            wrong = client.post(
                "/v1/tool/script_runner",
                json={},
                headers={"X-Tool-Token": "wrong-token"},
            )

        self.assertEqual(401, missing.status_code)
        self.assertEqual("REACTOR_TOOL_UNAUTHORIZED", missing.json()["code"])
        self.assertEqual(401, wrong.status_code)

    def test_tool_api_should_accept_header_and_bearer_tokens(self):
        with patch.dict(
            os.environ,
            {"REACTOR_TOOL_ENV": "test", "REACTOR_TOOL_TOKEN": "expected-token"},
            clear=False,
        ):
            client = TestClient(create_app())
            tool_header = client.post(
                "/v1/tool/script_runner",
                json={},
                headers={"X-Tool-Token": "expected-token"},
            )
            bearer = client.post(
                "/v1/tool/script_runner",
                json={},
                headers={"Authorization": "Bearer expected-token"},
            )

        # 已通过认证，空业务请求由 Pydantic 返回 422，而不是安全中间件的 401。
        self.assertEqual(422, tool_header.status_code)
        self.assertEqual(422, bearer.status_code)

    def test_health_and_signed_file_reads_should_remain_public_but_mutations_require_token(self):
        with patch.dict(
            os.environ,
            {"REACTOR_TOOL_ENV": "test", "REACTOR_TOOL_TOKEN": "expected-token"},
            clear=False,
        ):
            client = TestClient(create_app())
            health = client.get("/health")
            file_preview = client.get("/v1/file_tool/preview/missing/demo.txt")
            file_preview_head = client.head("/v1/file_tool/preview/missing/demo.txt")
            file_mutation = client.post("/v1/file_tool/get_file", json={})
            file_delete = client.delete("/v1/file_tool/file-id")
            document_mutation = client.post("/v1/documents/create_knowledge_base", json={})

        self.assertEqual(200, health.status_code)
        self.assertNotEqual(401, file_preview.status_code)
        self.assertNotEqual(401, file_preview_head.status_code)
        self.assertEqual(401, file_mutation.status_code)
        self.assertEqual(401, file_delete.status_code)
        self.assertEqual(401, document_mutation.status_code)

    def test_cors_should_only_allow_configured_frontend(self):
        with patch.dict(
            os.environ,
            {
                "REACTOR_TOOL_ENV": "test",
                "REACTOR_TOOL_TOKEN": "expected-token",
                "REACTOR_TOOL_CORS_ORIGINS": "http://127.0.0.1:5173",
            },
            clear=False,
        ):
            client = TestClient(create_app())
            allowed = client.options(
                "/v1/tool/script_runner",
                headers={
                    "Origin": "http://127.0.0.1:5173",
                    "Access-Control-Request-Method": "POST",
                    "Access-Control-Request-Headers": "x-tool-token,content-type",
                },
            )
            denied = client.options(
                "/v1/tool/script_runner",
                headers={
                    "Origin": "https://evil.example",
                    "Access-Control-Request-Method": "POST",
                },
            )

        self.assertEqual(200, allowed.status_code)
        self.assertEqual("http://127.0.0.1:5173", allowed.headers["access-control-allow-origin"])
        self.assertEqual(400, denied.status_code)
        self.assertNotIn("access-control-allow-origin", denied.headers)

if __name__ == "__main__":
    unittest.main()

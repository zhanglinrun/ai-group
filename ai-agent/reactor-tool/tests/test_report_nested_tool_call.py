import importlib
import unittest
from unittest.mock import AsyncMock, patch

from reactor_tool.model.protocal import ReportRequest


tool_api = importlib.import_module("reactor_tool.api.tool")


class ReportNestedToolCallTest(unittest.IsolatedAsyncioTestCase):
    async def test_non_stream_report_retries_nested_tool_call_before_upload(self):
        calls = []

        async def fake_report(**kwargs):
            calls.append(kwargs["task"])
            if len(calls) == 1:
                yield '```tool_code\nreport_tool(fileType="markdown")\n```'
            else:
                yield "# 终验报告\n\n仅包含已验证事实。"

        body = ReportRequest.model_validate({
            "requestId": "nested-tool-call",
            "query": "仅允许使用已验证事实。",
            "task": "生成终验报告",
            "fileName": "终验报告",
            "fileType": "markdown",
            "stream": False,
        })
        with patch("reactor_tool.tool.report.report", side_effect=fake_report), \
                patch.object(tool_api, "upload_file", new=AsyncMock(return_value={"fileName": "终验报告.md"})) as upload:
            response = await tool_api.post_report(body)

        self.assertEqual(2, len(calls))
        self.assertIn("你已处于 report_tool 内部", calls[1])
        self.assertEqual("# 终验报告\n\n仅包含已验证事实。", response["data"])
        upload.assert_awaited_once()
        self.assertEqual(response["data"], upload.await_args.kwargs["content"])

    async def test_strict_non_stream_report_sanitizes_before_upload(self):
        async def fake_report(**kwargs):
            yield "# 终验报告\n\n- 已验证：付费额度增加 60 点。\n- 虚构：`curl http://localhost:8082/health`"

        body = ReportRequest.model_validate({
            "requestId": "strict-sanitize",
            "query": "仅允许使用已验证事实，禁止补写未知信息。",
            "task": "生成终验报告",
            "fileName": "终验报告",
            "fileType": "markdown",
            "stream": False,
        })
        with patch("reactor_tool.tool.report.report", side_effect=fake_report), \
                patch.object(tool_api, "upload_file", new=AsyncMock(return_value={"fileName": "终验报告.md"})) as upload:
            response = await tool_api.post_report(body)

        self.assertIn("付费额度增加 60 点", response["data"])
        self.assertNotIn("curl", response["data"])
        upload.assert_awaited_once()
        self.assertEqual(response["data"], upload.await_args.kwargs["content"])

    async def test_strict_query_only_markdown_bypasses_model_generation(self):
        body = ReportRequest.model_validate({
            "requestId": "strict-deterministic",
            "query": "仅允许使用以下事实：\n- 注册、登录均已通过。\n- MCP 全称为 Model Context Protocol。",
            "task": "生成终验报告",
            "fileName": "终验报告",
            "fileType": "markdown",
            "stream": False,
        })
        with patch("reactor_tool.tool.report.report") as report_mock, \
                patch.object(tool_api, "upload_file", new=AsyncMock(return_value={"fileName": "终验报告.md"})):
            response = await tool_api.post_report(body)

        report_mock.assert_not_called()
        self.assertIn("注册、登录均已通过", response["data"])
        self.assertIn("Model Context Protocol", response["data"])

    async def test_strict_query_with_only_meta_instructions_fails_closed(self):
        body = ReportRequest.model_validate({
            "requestId": "strict-no-facts",
            "query": "仅允许使用以下事实：\n- 只允许使用上述事实，不要写未知信息。",
            "task": "生成终验报告",
            "fileName": "终验报告",
            "fileType": "markdown",
            "stream": False,
        })
        with patch("reactor_tool.tool.report.report") as report_mock, \
                patch.object(tool_api, "upload_file", new=AsyncMock()) as upload:
            with self.assertRaisesRegex(Exception, "未提供可提取的事实条目"):
                await tool_api.post_report(body)

        report_mock.assert_not_called()
        upload.assert_not_awaited()

    async def test_non_stream_report_rejects_second_nested_tool_call(self):
        async def fake_report(**kwargs):
            yield 'report_tool(fileType="markdown")'

        body = ReportRequest.model_validate({
            "requestId": "nested-tool-call-fails",
            "query": "只允许使用已验证事实。",
            "task": "生成终验报告",
            "fileName": "终验报告",
            "fileType": "markdown",
            "stream": False,
        })
        with patch("reactor_tool.tool.report.report", side_effect=fake_report), \
                patch.object(tool_api, "upload_file", new=AsyncMock()) as upload:
            with self.assertRaisesRegex(RuntimeError, "嵌套工具调用"):
                await tool_api.post_report(body)

        upload.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()

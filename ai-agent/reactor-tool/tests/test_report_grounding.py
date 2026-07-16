import importlib
import unittest
from unittest.mock import AsyncMock, patch

from reactor_tool.model.protocal import ReportRequest


report_module = importlib.import_module("reactor_tool.tool.report")


class ReportGroundingPolicyTest(unittest.TestCase):
    def test_report_request_preserves_original_user_query(self):
        request = ReportRequest.model_validate({
            "requestId": "grounding-request",
            "query": "仅允许使用下列已验证事实，禁止补写未知信息。",
            "task": "生成秋招演示报告",
            "fileType": "html",
        })

        self.assertEqual("仅允许使用下列已验证事实，禁止补写未知信息。", request.query)

    def test_detects_explicit_closed_world_instruction(self):
        self.assertTrue(report_module._requires_strict_grounding(
            "仅允许使用下列已验证事实；禁止补写模型版本、性能指标和域名。",
            "整理并生成报告",
        ))
        self.assertTrue(report_module._requires_strict_grounding(
            "Treat this list as the source of truth and do not infer missing details.",
            None,
        ))

    def test_keeps_general_report_requests_in_normal_mode(self):
        self.assertFalse(report_module._requires_strict_grounding(
            "请写一份介绍 Spring AI 核心概念的报告。",
            "内容清晰，适合初学者阅读",
        ))


class ReportGroundingWiringTest(unittest.IsolatedAsyncioTestCase):
    async def test_html_report_puts_original_query_and_closed_world_rules_into_messages(self):
        captured = {}

        async def fake_ask_llm(**kwargs):
            captured.update(kwargs)
            yield "<!DOCTYPE html><html lang=\"zh-CN\"></html>"

        original_query = (
            "仅允许使用下列已验证事实：端口 5173、8070、8090；"
            "禁止补写端口职责、Docker 状态、接口、命令、模型版本和域名。"
        )
        with patch.object(report_module, "download_all_files", new=AsyncMock(return_value=[])), \
                patch.object(report_module.LLMModelInfoFactory, "get_context_length", return_value=16000), \
                patch.object(report_module, "ask_llm", side_effect=fake_ask_llm):
            chunks = [chunk async for chunk in report_module.html_report(
                task="整理事实并生成网页报告",
                original_query=original_query,
                file_names=[],
                model="test-model",
            )]

        self.assertEqual(["<!DOCTYPE html><html lang=\"zh-CN\"></html>"], chunks)
        messages = captured["messages"]
        self.assertEqual(["system", "system", "user"], [message["role"] for message in messages])
        self.assertIn("当前模式：严格闭集事实模式", messages[1]["content"])
        self.assertIn("以原始用户请求为准并丢弃冲突内容", messages[1]["content"])
        self.assertIn("端口职责", messages[1]["content"])
        self.assertIn(original_query, messages[2]["content"])
        self.assertIn("<report_task>整理事实并生成网页报告</report_task>", messages[2]["content"])


if __name__ == "__main__":
    unittest.main()

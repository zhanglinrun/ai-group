import importlib
import unittest

from reactor_tool.model.protocal import ReportRequest


report_module = importlib.import_module("reactor_tool.tool.report")


class ReportGroundingPolicyTest(unittest.TestCase):
    def test_html_prompts_require_offline_self_contained_output(self):
        prompts = report_module.get_prompt("report")

        for prompt_name in ("html_prompt", "fix_html_prompt"):
            prompt = prompts[prompt_name]
            self.assertIn("可离线打开的单文件", prompt)
            self.assertNotIn("使用CDN", prompt)
            self.assertNotIn("<script src=", prompt)
            self.assertNotIn("<link rel=", prompt)
            self.assertNotIn("echarts.init", prompt)

    def test_report_request_preserves_original_user_query(self):
        request = ReportRequest.model_validate({
            "requestId": "grounding-request",
            "query": "仅允许使用下列已验证事实，禁止补写未知信息。",
            "task": "生成本地演示报告",
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
    async def test_html_report_renders_closed_world_boundary_without_model_invocation(self):
        original_query = (
            "仅允许使用下列已验证事实：端口 5173、8070、8090；"
            "禁止补写端口职责、Docker 状态、接口、命令、模型版本和域名。"
        )
        chunks = [chunk async for chunk in report_module.html_report(
            task="整理事实并生成网页报告",
            original_query=original_query,
            file_names=[],
        )]

        self.assertEqual(1, len(chunks))
        self.assertIn("Only supplied facts are rendered.", chunks[0])
        self.assertIn("整理事实并生成网页报告", chunks[0])


if __name__ == "__main__":
    unittest.main()

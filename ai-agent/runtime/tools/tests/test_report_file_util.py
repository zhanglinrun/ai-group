import unittest

from reactor_tool.util.report_file_util import (
    render_strict_query_markdown,
    sanitize_report_html_content,
    sanitize_strict_grounded_markdown,
)


class ReportFileUtilTest(unittest.TestCase):
    def test_should_strip_html_wrapper(self):
        wrapped = "Html:\n```html\n<html><body><h1>demo</h1></body></html>\n```"

        self.assertEqual(
            "<html><body><h1>demo</h1></body></html>",
            sanitize_report_html_content(wrapped),
        )

    def test_should_keep_plain_html_untouched(self):
        plain = "<html><body><h1>demo</h1></body></html>"

        self.assertEqual(plain, sanitize_report_html_content(plain))

    def test_should_remove_unsupported_strict_markdown_claims(self):
        content = """# 终验报告

- 已验证：支付 ¥12 后付费额度增加 60 点，冻结额度为 0。
- 未提供命令：`curl http://localhost:8082/health`
- 未提供指标：状态在 <100ms 内更新。
- 虚构引用：[[1]](https://example.com/fake)
- 已验证：MCP 全称为 Model Context Protocol。
"""

        sanitized = sanitize_strict_grounded_markdown(content)

        self.assertIn("支付 ¥12 后付费额度增加 60 点，冻结额度为 0", sanitized)
        self.assertIn("Model Context Protocol", sanitized)
        self.assertNotIn("curl", sanitized)
        self.assertNotIn("100ms", sanitized)
        self.assertNotIn("example.com", sanitized)

    def test_should_render_strict_query_without_new_facts(self):
        query = """仅允许使用以下事实：
- 注册、登录均已通过。
- 支付 ¥12 后订单状态为 BENEFIT_GRANTED，付费额度增加 60 点，冻结额度为 0。
- MCP 全称为 Model Context Protocol。
禁止补写未知信息。
"""

        report = render_strict_query_markdown(query, "终验报告")

        self.assertIn("# 终验报告", report)
        self.assertIn("注册、登录均已通过", report)
        self.assertIn("BENEFIT_GRANTED", report)
        self.assertIn("Model Context Protocol", report)
        self.assertNotIn("http://", report)
        self.assertNotIn("接口", report)

    def test_should_remove_inline_meta_instructions_from_fact_bullets(self):
        query = """以下列表是穷尽的 source-of-truth：
- 注册、登录均已通过。
- AI Group 使用 5173；另一个本地服务使用 8082；Docker 映射互不重叠。除上述事实外，不要写任何端口用途、服务职责、接口、命令或 Docker 细节。
- MCP 全称只能写 Model Context Protocol。
- 8082 端口未被 AI Group 占用或重启。
- 报告必须包含：结论；未给出的内容统一写“未在本轮验证”。
"""

        report = render_strict_query_markdown(query, "终验报告")

        self.assertIn("注册、登录均已通过", report)
        self.assertIn("Docker 映射互不重叠", report)
        self.assertIn("MCP 全称为 Model Context Protocol", report)
        self.assertIn("8082 端口未被 AI Group 占用或重启", report)
        self.assertNotIn("不要写任何端口用途", report)
        self.assertNotIn("MCP 全称只能写", report)
        self.assertNotIn("报告必须包含", report)
        self.assertNotIn("未给出的内容统一写", report)

    def test_should_reject_strict_query_with_only_meta_instructions(self):
        query = """以下列表是穷尽的 source-of-truth：
- 只允许使用上述事实，不要写未知信息。
- 报告必须包含结论。
"""

        with self.assertRaisesRegex(ValueError, "未提供可提取的事实条目"):
            render_strict_query_markdown(query, "终验报告")


if __name__ == "__main__":
    unittest.main()

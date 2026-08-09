from __future__ import annotations

from service.qa.rules import RuleResult

# Maps each QA rule id to a concrete, actionable Chinese rewrite instruction the
# writer can follow on the next attempt. Without this, the writer only receives
# raw rule ids / English findings, which makes the feedback loop's rewrites less
# targeted. Unknown rule ids fall back to the rule's own message in
# build_remediation_hints, so this dict can stay partial without raising.
RULE_REMEDIATION_HINTS: dict[str, str] = {
    "rule_report_must_have_markdown_content": (
        "报告 markdown 不能为空；请基于已有 evidence 重写各 section 的 content_markdown。"
    ),
    "rule_report_template_id_present": (
        "输出 JSON 必须包含非空 template_id 字段。"
    ),
    "rule_report_must_have_at_least_one_section": (
        "报告至少需要一个 section；每个 section 需包含 section_id、title、content_markdown。"
    ),
    "rule_report_section_count_in_bounds": (
        "section 数量需在 1-12 之间；删除冗余章节或合并同类内容。"
    ),
    "rule_writer_sections_must_have_content": (
        "每个 section 的 content_markdown 需超过 60 字；"
        "数据不足时写明「当前证据不足以支撑该维度结论」而非留空。"
    ),
    "rule_writer_must_cite_evidence": (
        "请在每个 section 的 evidence_refs 中引用至少一条真实采集的 evidence_id，"
        "不得使用占位符或虚构 ID。"
    ),
    "rule_writer_no_fallback_mode": (
        "不得输出 writer_fallback_mode；需基于 analyst insights 与 evidence 完成 LLM 写作。"
    ),
    "rule_evidence_must_be_desensitized": (
        "证据未脱敏；需回到 researcher 重新采集并确保 desensitized=true。"
    ),
    "rule_deep_report_min_char_count": (
        "深度报告总字数不足；扩展各 section 的具体对比与引用，避免泛泛而谈。"
    ),
    "rule_deep_report_covers_target_sections": (
        "未覆盖全部目标 section；缺失的 section 需补写或标注 data insufficient 并引用现有 evidence。"
    ),
    "rule_deep_sections_min_chars": (
        "部分 section 正文过短；每个 section 需至少 220 字（深度报告）或 60 字（快速报告）。"
    ),
    "rule_deep_sections_cite_evidence": (
        "深度报告每个 section 需至少引用 1 条 evidence；检查 evidence_refs 是否为空。"
    ),
}


def build_remediation_hints(failed_rules: list[RuleResult]) -> dict[str, str]:
    """Translate failed QA rules into actionable rewrite hints keyed by rule id.

    Falls back to the rule's own message when no curated hint exists, so the
    writer always receives something concrete per failed rule.
    """
    hints: dict[str, str] = {}
    for item in failed_rules:
        hints[item.rule_id] = RULE_REMEDIATION_HINTS.get(item.rule_id, item.message)
    return hints

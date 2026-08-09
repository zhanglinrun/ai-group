from __future__ import annotations

from agents.nodes.intake import (
    _apply_patch,
    _ambiguous_term_clarify,
    _merge_reply_into_draft,
    _clarify_target_satisfied,
    _fallback_clarify,
    _needs_ambiguous_term_clarify,
    _should_drop_optional_clarify,
    _unsatisfied_clarify_targets,
)
from schemas.intake import IntakeClarifyRequest, IntakeExchange, IntakeUserReply, RunIntakeDraft
from service.locale import detect_language
from service.llm.prompts import INTAKE_SYSTEM_PROMPT


def test_intake_prompt_uses_cross_domain_examples() -> None:
    assert "供应链 SaaS" in INTAKE_SYSTEM_PROMPT
    assert "CRM tools" in INTAKE_SYSTEM_PROMPT
    assert "AI 编程工具" in INTAKE_SYSTEM_PROMPT
    assert "供应链 ERP 调研" in INTAKE_SYSTEM_PROMPT
    assert "CRM 续费风险" in INTAKE_SYSTEM_PROMPT


def test_intake_prompt_removes_specific_ai_coding_title_templates() -> None:
    assert "TRAE" not in INTAKE_SYSTEM_PROMPT
    assert "Copilot" not in INTAKE_SYSTEM_PROMPT
    assert "[产品A] vs [产品B]" in INTAKE_SYSTEM_PROMPT


def test_detect_language_uses_chinese_character_ratio() -> None:
    assert detect_language("工业自动化设备销售团队要找国内 AI 工具") == "zh"
    assert detect_language("Compare CRM sales intelligence tools") == "en"
    assert detect_language("CRM 工具 compare pricing") == "zh"
    assert detect_language("") == "en"


def test_intake_prompt_exposes_response_language_contract() -> None:
    assert '"response_language": "zh" | "en" | null' in INTAKE_SYSTEM_PROMPT
    assert "response_language defaults to the detected language of user_query" in INTAKE_SYSTEM_PROMPT


def test_intake_prompt_requires_polysemous_acronym_disambiguation() -> None:
    assert "polysemous acronyms" in INTAKE_SYSTEM_PROMPT
    assert "OPC" in INTAKE_SYSTEM_PROMPT
    assert "domain_hint" in INTAKE_SYSTEM_PROMPT


def test_intake_prompt_keeps_report_depth_outside_intake_stage() -> None:
    assert "Never ask report_depth in intake." in INTAKE_SYSTEM_PROMPT
    assert '"report_depth": "quick" | "deep" | null' not in INTAKE_SYSTEM_PROMPT
    assert 'report_depth ("quick"|"deep")' not in INTAKE_SYSTEM_PROMPT


def test_intake_prompt_enforces_professional_user_facing_wording() -> None:
    assert "User-facing wording contract" in INTAKE_SYSTEM_PROMPT
    assert "clarify_request.question" in INTAKE_SYSTEM_PROMPT
    assert "竞品 / 厂商 / 产品 / 企业 / 参与方" in INTAKE_SYSTEM_PROMPT
    assert "Avoid colloquial labels such as 玩家、玩具、玩具型." in INTAKE_SYSTEM_PROMPT


def test_apply_patch_accepts_response_language_override() -> None:
    draft = RunIntakeDraft(user_query="请用英文输出国内销售工具分析")

    next_draft = _apply_patch(draft, {"response_language": "en"})

    assert next_draft.response_language == "en"


def test_apply_patch_normalizes_unknown_self_product_to_none() -> None:
    draft = RunIntakeDraft(user_query="AI硬件全景与趋势", self_product="AI硬件")

    next_draft = _apply_patch(draft, {"self_product": "不知道"})

    assert next_draft.self_product is None


def test_apply_patch_does_not_narrow_broad_ai_hardware_to_ai_glasses() -> None:
    draft = RunIntakeDraft(user_query="AI硬件全景与趋势")

    next_draft = _apply_patch(
        draft,
        {
            "target_category": "AI眼镜",
            "category_aliases": ["AI眼镜"],
            "market_segments": ["AI眼镜"],
        },
    )

    assert next_draft.target_category == "AI硬件"
    assert "AI眼镜" in next_draft.market_segments


def test_ambiguous_opc_requires_clarify_before_complete() -> None:
    draft = RunIntakeDraft(
        user_query="在 AI 时代有哪些能赚钱的 OPC 项目？",
        user_role="founder",
        analysis_intent="寻找 OPC 变现项目",
        competitors_discovery_mode=True,
    )

    assert draft.is_complete is True
    assert _needs_ambiguous_term_clarify(draft=draft, history=[]) is True
    clarify = _ambiguous_term_clarify(draft)
    assert clarify.field_targets == ["domain_hint", "analysis_intent"]
    assert any("One Person Company" in option for option in (clarify.suggested_options or []))


def test_merge_opc_disambiguation_rewrites_domain_and_intent() -> None:
    draft = RunIntakeDraft(
        user_query="OPC 项目怎么变现？",
        user_role="founder",
        analysis_intent="寻找 OPC 变现项目",
        competitors_discovery_mode=True,
    )
    clarify = IntakeClarifyRequest(
        question="OPC 指什么？",
        field_targets=["domain_hint", "analysis_intent"],
    )
    reply = IntakeUserReply(text="我指一人公司/个人可落地变现项目")

    next_draft = _merge_reply_into_draft(draft, clarify, reply)

    assert next_draft.domain_hint == "one person company monetization"
    assert "一人公司" in str(next_draft.analysis_intent)
    history = [IntakeExchange(clarify=clarify, reply=reply)]
    assert _needs_ambiguous_term_clarify(draft=next_draft, history=history) is False


def test_fallback_clarify_analysis_intent_is_domain_neutral() -> None:
    draft = RunIntakeDraft(
        user_query="我是产品经理，想调研供应链 ERP 的实施与集成差异。",
        user_role="pm",
    )

    clarify = _fallback_clarify(draft)

    assert clarify.field_targets == ["analysis_intent"]
    assert clarify.suggested_answer is not None
    assert "目标赛道" in clarify.suggested_answer
    assert "AI 编程" not in clarify.suggested_answer
    assert "TRAE" not in clarify.suggested_answer
    assert "Copilot" not in clarify.suggested_answer


def test_clarify_target_satisfied_tracks_completion_fields() -> None:
    draft = RunIntakeDraft(
        user_query="对比 Notion 和 Cursor 的定价策略",
        user_role="pm",
        analysis_intent="对比定价",
        competitors_explicit=["Notion", "Cursor"],
    )

    assert _clarify_target_satisfied("user_role", draft) is True
    assert _clarify_target_satisfied("analysis_intent", draft) is True
    assert _clarify_target_satisfied("competitors_explicit", draft) is True
    assert _clarify_target_satisfied("competitors_discovery_mode", draft) is True
    assert _clarify_target_satisfied("market_scope", draft) is False

    scoped = draft.model_copy(update={"market_scope": "中国 / China"})
    assert _clarify_target_satisfied("market_scope", scoped) is True


def test_unsatisfied_targets_empty_when_user_already_supplied_required_fields() -> None:
    # R8: complete draft + LLM re-asking a field the user already gave (user_role).
    draft = RunIntakeDraft(
        user_query="我是 pm，对比 Notion 和 Cursor 的定价策略",
        user_role="pm",
        analysis_intent="对比定价",
        competitors_explicit=["Notion", "Cursor"],
    )
    clarify = IntakeClarifyRequest(
        question="请问您的角色是？",
        field_targets=["user_role"],
    )

    assert draft.is_complete is True
    assert _unsatisfied_clarify_targets(clarify, draft) == []


def test_unsatisfied_targets_preserves_genuinely_new_question() -> None:
    draft = RunIntakeDraft(
        user_query="对比 Notion 和 Cursor",
        user_role="pm",
        analysis_intent="对比定价",
        competitors_discovery_mode=True,
    )
    clarify = IntakeClarifyRequest(
        question="想补充关注的分析维度吗？",
        field_targets=["focus_dimensions"],
    )

    assert _unsatisfied_clarify_targets(clarify, draft) == ["focus_dimensions"]


def test_merge_reply_uses_selected_options_for_optional_text_fields() -> None:
    draft = RunIntakeDraft(
        user_query="找 one person company 方向",
        user_role="founder",
        analysis_intent="寻找适合一人公司的可变现方向",
        competitors_discovery_mode=True,
    )

    next_draft = _merge_reply_into_draft(
        draft,
        IntakeClarifyRequest(
            question="您主要关注哪个市场区域？",
            field_targets=["market_scope"],
            suggested_options=["全球 / Global", "中国 / China"],
        ),
        IntakeUserReply(text="", selected_options=["中国 / China"]),
    )

    assert next_draft.market_scope == "中国 / China"


def test_optional_clarify_repeat_is_dropped_after_complete_draft() -> None:
    history = [
        IntakeExchange(
            clarify=IntakeClarifyRequest(
                question="您主要关注哪个市场区域？",
                field_targets=["market_scope"],
            ),
            reply=IntakeUserReply(text="", selected_options=["中国 / China"]),
        )
    ]
    clarify = IntakeClarifyRequest(
        question="为了筛选高变现潜力方向，您希望重点考察哪个市场区域？",
        field_targets=["market_scope"],
    )

    assert _should_drop_optional_clarify(clarify, history) is True

from __future__ import annotations

from service.knowledge import extract_knowledge_schema


def test_extract_knowledge_schema_populates_triplet_for_comparison() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "ev_cursor_feature",
                "competitor_id": "Cursor",
                "dimension": "feature",
                "quote_preview": "Cursor supports repo-aware edits.",
                "source_title": "Cursor Features",
            },
            {
                "evidence_id": "ev_cursor_pricing",
                "competitor_id": "Cursor",
                "dimension": "pricing",
                "quote_preview": "Cursor monthly subscription is published.",
                "source_title": "Cursor Pricing",
            },
            {
                "evidence_id": "ev_cursor_feedback",
                "competitor_id": "Cursor",
                "dimension": "user_feedback",
                "quote_preview": "Teams want faster review cycles.",
                "source_title": "Cursor Reviews",
            },
            {
                "evidence_id": "ev_windsurf_feature",
                "competitor_id": "Windsurf",
                "dimension": "feature",
                "quote_preview": "Windsurf highlights collaborative workflows.",
                "source_title": "Windsurf Features",
            },
            {
                "evidence_id": "ev_windsurf_pricing",
                "competitor_id": "Windsurf",
                "dimension": "pricing_strategy",
                "quote_preview": "Windsurf has annual enterprise bundles.",
                "source_title": "Windsurf Pricing",
            },
            {
                "evidence_id": "ev_windsurf_feedback",
                "competitor_id": "Windsurf",
                "dimension": "user_feedback",
                "quote_preview": "Admins ask for stronger governance controls.",
                "source_title": "Windsurf Reviews",
            },
        ],
        competitors=["Cursor", "Windsurf"],
        focus_dimensions=["feature", "pricing", "user_feedback"],
        analysis_archetype="comparison",
    )

    assert result.extraction_mode == "comparison"
    assert result.schema_version == "schema_v0.2"
    assert len(result.features) >= 2
    assert len(result.pricings) == 2
    assert len(result.personas) == 2
    assert len(result.feedback) == 2
    assert set(result.coverage.keys()) == {"Cursor", "Windsurf"}
    assert result.coverage["Cursor"]["pricing"] == "partial"
    assert result.coverage["Windsurf"]["feedback"] == "partial"
    assert all(item["evidence_ids"] for item in result.features)
    assert all(item["evidence_ids"] for item in result.pricings)
    assert all(item["evidence_ids"] for item in result.personas)
    assert all(item["evidence_ids"] for item in result.feedback)


def test_extract_knowledge_schema_filters_invalid_and_unknown_competitors() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "",
                "competitor_id": "Cursor",
                "dimension": "feature",
                "quote_preview": "missing evidence id",
                "source_title": "bad row",
            },
            {
                "evidence_id": "ev_unknown_competitor",
                "competitor_id": "UnknownTool",
                "dimension": "feature",
                "quote_preview": "unknown competitor should be filtered",
                "source_title": "unknown",
            },
            {
                "evidence_id": "ev_cursor_pricing",
                "competitor_id": "Cursor",
                "dimension": "pricing",
                "quote_preview": "cursor price",
                "source_title": "Cursor Pricing",
            },
        ],
        competitors=["Cursor"],
        focus_dimensions=["pricing"],
        analysis_archetype="comparison",
    )

    assert result.features == []
    assert len(result.pricings) == 1
    assert result.pricings[0]["competitor_id"] == "Cursor"
    assert result.coverage == {
        "Cursor": {
            "feature": "insufficient_data",
            "pricing": "partial",
            "feedback": "insufficient_data",
            "persona": "insufficient_data",
        }
    }
    assert result.missing_reasons["Cursor"] == [
        "feature:no_grounded_evidence",
        "pricing:tier_details_missing",
        "feedback:no_grounded_evidence",
        "persona:no_grounded_evidence",
    ]


def test_extract_knowledge_schema_recovers_pricing_from_quote_text() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "ev_cursor_pricing_text",
                "competitor_id": "Cursor",
                "dimension": "feature",
                "quote_preview": "Cursor Pro is priced at $20/month, with Business and Enterprise tiers.",
                "source_title": "Cursor pricing details",
            }
        ],
        competitors=["Cursor"],
        focus_dimensions=["feature", "pricing", "user_feedback"],
        analysis_archetype="comparison",
    )

    assert result.features == []
    assert len(result.pricings) == 1
    assert result.pricings[0]["competitor_id"] == "Cursor"
    assert result.coverage["Cursor"]["pricing"] == "partial"


def test_extract_knowledge_schema_generates_schema_in_landscape_mode() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "ev_landscape",
                "competitor_id": "DeepSeek",
                "dimension": "monetization_paths",
                "quote_preview": "landscape signal with monetization context",
                "source_title": "landscape",
            }
        ],
        competitors=["DeepSeek"],
        focus_dimensions=["monetization_paths"],
        analysis_archetype="landscape",
    )

    assert result.extraction_mode == "landscape"
    assert len(result.features) == 1
    assert result.features[0]["competitor_id"] == "DeepSeek"
    assert result.pricings == []
    assert result.personas == []
    assert result.feedback == []
    assert result.coverage == {
        "DeepSeek": {
            "feature": "partial",
            "pricing": "insufficient_data",
            "feedback": "insufficient_data",
            "persona": "insufficient_data",
        }
    }
    assert result.missing_reasons["DeepSeek"] == [
        "feature:coverage_partial",
        "pricing:no_grounded_evidence",
        "feedback:no_grounded_evidence",
        "persona:no_grounded_evidence",
    ]


def test_extract_knowledge_schema_landscape_without_role_keeps_triplet_required() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[],
        competitors=["DeepSeek"],
        focus_dimensions=["monetization_paths"],
        analysis_archetype="landscape",
    )

    assert result.extraction_mode == "landscape"
    assert result.features == []
    assert result.pricings == []
    assert result.personas == []
    assert result.feedback == []
    assert result.coverage == {
        "DeepSeek": {
            "feature": "insufficient_data",
            "pricing": "insufficient_data",
            "feedback": "insufficient_data",
            "persona": "insufficient_data",
        }
    }
    assert result.missing_reasons["DeepSeek"] == [
        "feature:no_grounded_evidence",
        "pricing:no_grounded_evidence",
        "feedback:no_grounded_evidence",
        "persona:no_grounded_evidence",
    ]


def test_extract_knowledge_schema_landscape_peripheral_role_allows_not_applicable() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[],
        competitors=["CAICT"],
        focus_dimensions=["monetization_paths"],
        analysis_archetype="landscape",
        competitor_roles={"CAICT": "trend_reference"},
    )

    assert result.coverage == {
        "CAICT": {
            "feature": "not_applicable_for_archetype",
            "pricing": "not_applicable_for_archetype",
            "feedback": "not_applicable_for_archetype",
            "persona": "not_applicable_for_archetype",
        }
    }
    assert result.missing_reasons["CAICT"] == [
        "feature:not_applicable_for_archetype",
        "pricing:not_applicable_for_archetype",
        "feedback:not_applicable_for_archetype",
        "persona:not_applicable_for_archetype",
    ]


def test_extract_knowledge_schema_requires_pricing_when_landscape_requests_pricing() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[],
        competitors=["DeepSeek"],
        focus_dimensions=["pricing"],
        analysis_archetype="landscape",
    )

    assert result.coverage["DeepSeek"]["pricing"] == "insufficient_data"
    assert "pricing:no_grounded_evidence" in result.missing_reasons["DeepSeek"]


def test_extract_knowledge_schema_complete_requires_target_category_evidence() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "ev_adjacent_feature_1",
                "competitor_id": "Meta",
                "dimension": "feature",
                "quote_preview": "Meta 手机业务新闻不属于 AI 眼镜目标品类。",
                "source_title": "Meta adjacent business",
                "category_relevance": "adjacent_segment",
            },
            {
                "evidence_id": "ev_adjacent_feature_2",
                "competitor_id": "Meta",
                "dimension": "feature",
                "quote_preview": "Meta VR 业务邻近但不能单独支撑 AI 眼镜 complete。",
                "source_title": "Meta adjacent VR",
                "category_relevance": "adjacent_segment",
            },
            {
                "evidence_id": "ev_adjacent_feature_3",
                "competitor_id": "Meta",
                "dimension": "feature",
                "quote_preview": "Meta 泛硬件能力仍缺少目标品类证据。",
                "source_title": "Meta adjacent hardware",
                "category_relevance": "adjacent_segment",
            },
        ],
        competitors=["Meta"],
        focus_dimensions=["feature"],
        analysis_archetype="landscape",
    )

    assert result.coverage["Meta"]["feature"] == "partial"
    assert result.supporting_target_evidence_ids == {}
    assert "feature:category_mismatch" in result.missing_reasons["Meta"]


def test_extract_knowledge_schema_filters_off_topic_evidence() -> None:
    result = extract_knowledge_schema(
        evidence_briefs=[
            {
                "evidence_id": "ev_oceanstor_feature",
                "competitor_id": "Huawei",
                "dimension": "feature",
                "quote_preview": "Huawei OceanStor storage feature is unrelated to AI hardware endpoint products.",
                "source_title": "Huawei OceanStor",
                "category_relevance": "off_topic",
            }
        ],
        competitors=["Huawei"],
        focus_dimensions=["feature"],
        analysis_archetype="landscape",
    )

    assert result.features == []
    assert result.coverage["Huawei"]["feature"] == "insufficient_data"
    assert result.supporting_target_evidence_ids == {}

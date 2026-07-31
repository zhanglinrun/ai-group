package com.linrun.agent.domain.agent.runtime.tool.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a grounded report specification before any artifact delivery step. */
public class WriteReportSpecTool implements BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "write_report_spec";
    }

    @Override
    public String getDescription() {
        return "创建报告结构规格，明确标题、受众、格式、章节和证据引用。";
    }

    @Override
    public Map<String, Object> toParams() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "executiveSummary", Map.of("type", "string"),
                        "methodology", Map.of("type", "string"),
                        "sections", Map.of("type", "array", "items", Map.of("type", "object")),
                        "claims", Map.of("type", "array", "items", Map.of("type", "object")),
                        "citations", Map.of("type", "array", "items", Map.of("type", "object")),
                        "conflicts", Map.of("type", "array", "items", Map.of("type", "object")),
                        "limitations", Map.of("type", "array", "items", Map.of("type", "string")),
                        "generatedAt", Map.of("type", "string"),
                        "rendererVersion", Map.of("type", "string")),
                "required", List.of("title", "sections", "claims", "citations"),
                "additionalProperties", false);
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof Map<?, ?> raw)) {
            return failure("write_report_spec input must be an object");
        }
        if (text(raw.get("title")).isBlank()) {
            return failure("title is required");
        }
        if (!(raw.get("sections") instanceof List<?> sections) || sections.isEmpty() || sections.size() > 12) {
            return failure("sections must contain between 1 and 12 items");
        }
        ReportSpec spec = ReportSpec.from(raw);
        try {
            Map<String, Object> output = new LinkedHashMap<>(spec.toMap());
            output.put("grounding_required", true);
            output.put("requestedEvidenceRefs", strings(raw.get("evidence_refs")));
            String serialized = MAPPER.writeValueAsString(output);
            return ToolResultPayload.structured(serialized, serialized, spec);
        } catch (Exception error) {
            return failure("report spec serialization failed");
        }
    }

    @Override
    public boolean isConcurrencySafe(Object input) {
        return true;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            String candidate = text(item);
            if (!candidate.isBlank()) {
                values.add(candidate);
            }
        }
        return List.copyOf(values);
    }

    private ToolResultPayload failure(String message) {
        return ToolResultPayload.failure(message, message, null, message);
    }
}

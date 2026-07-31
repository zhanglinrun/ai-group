package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactBinding;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates the user-requested DEEP delivery through the existing run-local
 * ToolDispatcher. This component never opens another model loop: it builds a
 * deterministic tool call after research review has completed.
 */
@Service
public class ToolDispatcherDeepResearchArtifactDelivery implements DeepResearchArtifactDelivery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentLoopFactory agentLoopFactory;

    public ToolDispatcherDeepResearchArtifactDelivery(AgentLoopFactory agentLoopFactory) {
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "AgentLoopFactory must not be null");
    }

    @Override
    public List<ToolArtifactBinding> deliver(AgentContext context,
                                             AgentRequest request,
                                             String checkpointThreadId,
                                             String canonicalMarkdown) throws Exception {
        return deliver(context, request, checkpointThreadId, null, canonicalMarkdown);
    }

    @Override
    public List<ToolArtifactBinding> deliver(AgentContext context,
                                             AgentRequest request,
                                             String checkpointThreadId,
                                             ReportSpec reportSpec,
                                             String canonicalMarkdown) throws Exception {
        DeliverySpec spec = DeliverySpec.forOutputStyle(request == null ? null : request.getOutputStyle());
        if (spec == null) {
            return List.of();
        }
        ToolCollection tools = context == null ? null : context.getToolCollection();
        if (tools == null || tools.getTool(spec.toolName()) == null) {
            throw new IllegalStateException("Deep research delivery requires unavailable tool: " + spec.toolName());
        }

        String fileStem = "deep_research_" + StringUtils.defaultIfBlank(checkpointThreadId, "delivery")
                .replace("-", "");
        String toolCallId = "deep-research-delivery:" + spec.outputStyle();
        AgentLoop loop = agentLoopFactory.create(context);
        ToolCall deliveryCall = ToolCall.builder()
                        .id(toolCallId)
                        .type("function")
                        .function(ToolCall.Function.builder()
                        .name(spec.toolName())
                        .arguments(arguments(spec, fileStem, canonicalMarkdown, reportSpec))
                        .build())
                .build();
        ToolExecutionOutcome outcome = loop.executeToolOutcome(deliveryCall);
        if (outcome == null || !outcome.isSuccess()) {
            throw new IllegalStateException("Deep research " + spec.outputStyle()
                    + " delivery tool failed: " + (outcome == null ? "missing outcome" : outcome.getErrorMsg()));
        }
        recoverCodeInterpreterArtifacts(context, deliveryCall, outcome);

        List<ToolArtifactBinding> matchingArtifacts = context.getArtifactBindingsByToolCallId(toolCallId).stream()
                .filter(binding -> hasExpectedExtension(binding == null ? null : binding.getFile(), spec.extensions()))
                .toList();
        if (matchingArtifacts.isEmpty()) {
            String observedNames = context.getArtifactBindingsByToolCallId(toolCallId).stream()
                    .map(ToolArtifactBinding::getFile)
                    .filter(Objects::nonNull)
                    .map(File::getFileName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Deep research " + spec.outputStyle()
                    + " delivery did not produce a required " + spec.extensions()
                    + " artifact; observed=" + observedNames);
        }
        return matchingArtifacts;
    }

    /**
     * Normally CodeInterpreterTool registers artifacts while projecting the
     * terminal SSE frame. Preserve delivery if a reconnect supplies the typed
     * outcome before that callback reaches the run-local registry.
     */
    private void recoverCodeInterpreterArtifacts(AgentContext context,
                                                 ToolCall call,
                                                 ToolExecutionOutcome outcome) {
        if (context == null || call == null || call.getFunction() == null || outcome == null
                || !"code_interpreter".equals(call.getFunction().getName())
                || !context.getArtifactBindingsByToolCallId(call.getId()).isEmpty()
                || !(outcome.getStructuredOutput() instanceof CodeInterpreterToolOutput output)) {
            return;
        }
        ToolArtifactSource source = ToolArtifactSource.builder()
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId(call.getId())
                .toolName(call.getFunction().getName())
                .build();
        for (ToolFileRef ref : output.getFileRefs()) {
            if (ref == null || StringUtils.isBlank(ref.getFileName())) {
                continue;
            }
            context.registerGeneratedArtifact(source, File.builder()
                    .fileName(ref.getFileName())
                    .ossUrl(StringUtils.defaultIfBlank(ref.getOssUrl(), ref.getDownloadUrl()))
                    .domainUrl(StringUtils.defaultIfBlank(ref.getDomainUrl(), ref.getPreviewUrl()))
                    .fileSize(ref.getFileSize() == null ? null : Math.toIntExact(ref.getFileSize()))
                    .description("Deep Research table delivery")
                    .isInternalFile(false)
                    .build());
        }
    }

    private String arguments(DeliverySpec spec,
                             String fileStem,
                             String canonicalMarkdown,
                             ReportSpec reportSpec) throws JsonProcessingException {
        String task = switch (spec.outputStyle()) {
            case "markdown" -> "仅依据已审核的 ReportSpec 生成 Markdown 报告。保留 evidence id、来源 URL、hash 和不确定性标签；"
                    + "不得添加未验证的来源或结论。\n\n" + canonicalMarkdown;
            case "pdf" -> "仅依据已审核的 ReportSpec 生成 PDF 报告。保留 evidence id、来源 URL、hash 和不确定性标签；"
                    + "不得添加未验证的来源或结论。\n\n" + canonicalMarkdown;
            case "ppt" -> "仅依据以下已审核的 Deep Research Markdown 生成 PPTX。保留来源 URL 与证据不足说明；"
                    + "不得添加未验证的来源或结论。\n\n" + canonicalMarkdown;
            case "html" -> "仅依据以下已审核的 Deep Research Markdown 生成 HTML 网页。保留来源 URL 与证据不足说明；"
                    + "不得添加未验证的来源或结论。\n\n" + canonicalMarkdown;
            case "table" -> "仅依据以下已审核的 Deep Research Markdown 生成 CSV 表格，"
                    + "第一行必须是列名，至少包含 section、claim、source_url、evidence_status 四列。"
                    + "将最终文件保存为 " + fileStem + ".csv；不得添加未验证的来源或结论。\n\n"
                    + canonicalMarkdown;
            default -> throw new IllegalArgumentException("Unsupported DEEP delivery style: " + spec.outputStyle());
        };
        if ("report_tool".equals(spec.toolName())) {
            Map<String, Object> arguments = new java.util.LinkedHashMap<>();
            arguments.put("task", task);
            arguments.put("fileName", fileStem);
            arguments.put("fileDescription", "Deep Research " + spec.outputStyle() + " delivery");
            arguments.put("fileType", spec.reportFileType());
            if (reportSpec != null) {
                arguments.put("reportSpec", reportSpec.toMap());
            }
            return JSON.writeValueAsString(arguments);
        }
        String code = deterministicCsvCode(fileStem + ".csv", canonicalMarkdown);
        // code_interpreter deliberately refuses natural-language planning. The same explicit
        // payload is carried in task for the legacy worker route and in code for the tool
        // schema, so both boundaries verify that this is deterministic sandbox work.
        Map<String, Object> sandboxPayload = Map.of(
                "code", code,
                "permissionProfile", "analysis"
        );
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("task", JSON.writeValueAsString(sandboxPayload));
        arguments.put("code", code);
        arguments.put("permissionProfile", "analysis");
        arguments.put("fileName", fileStem + ".csv");
        arguments.put("fileDescription", "Deep Research table delivery");
        return JSON.writeValueAsString(arguments);
    }

    private String deterministicCsvCode(String fileName, String canonicalMarkdown) throws JsonProcessingException {
        String markdownLiteral = JSON.writeValueAsString(StringUtils.defaultString(canonicalMarkdown));
        String fileNameLiteral = JSON.writeValueAsString(fileName);
        return "markdown = " + markdownLiteral + "\n"
                + "claim = markdown.replace('\\r', ' ').replace('\\n', ' ').replace('\\\"', '\\\"\\\"')\n"
                + "csv = 'section,claim,source_url,evidence_status\\n' + 'Deep Research,\"' + claim + '\",,DEGRADED\\n'\n"
                + "write_text_file(build_output_path(" + fileNameLiteral + "), csv)\n";
    }

    private boolean hasExpectedExtension(File file, Set<String> extensions) {
        if (file == null || StringUtils.isBlank(file.getFileName())) {
            return false;
        }
        String fileName = file.getFileName().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(extension -> fileName.endsWith(extension));
    }

    private record DeliverySpec(String outputStyle,
                                String toolName,
                                String reportFileType,
                                Set<String> extensions) {

        private static DeliverySpec forOutputStyle(String outputStyle) {
            String normalized = StringUtils.defaultIfBlank(outputStyle, "markdown").trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                // Chat stays inline, while explicit document output is delivered
                // through the deterministic Python ReportSpec renderer.
                case "chat", "text" -> null;
                case "markdown", "docs", "document" -> new DeliverySpec("markdown", "report_tool", "markdown", Set.of(".md"));
                case "ppt", "pptx" -> new DeliverySpec("ppt", "report_tool", "ppt", Set.of(".pptx"));
                case "html", "web" -> new DeliverySpec("html", "report_tool", "html", Set.of(".html", ".htm"));
                case "pdf" -> new DeliverySpec("pdf", "report_tool", "pdf", Set.of(".pdf"));
                case "table", "csv", "xlsx" -> new DeliverySpec("table", "code_interpreter", null,
                        Set.of(".csv", ".xlsx"));
                default -> throw new IllegalArgumentException("Unsupported DEEP output style: " + outputStyle);
            };
        }
    }
}

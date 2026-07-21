package com.linrun.agent.domain.agent.reactor.config;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Reactor Phase 1 期间保留在 domain 的过渡态共享配置契约。
 * 删除/迁移时机：当 Reactor 共享配置被单独 change 收敛到 app 或专用配置模块后再迁移；
 * 当前阶段禁止顺手改写读取语义。
 */
@Slf4j
@Getter
@Configuration
public class ReactorConfig {

    private Map<String, String> agentLoopSystemPromptMap = new HashMap<>();
    @Value("${autobots.autoagent.agent-loop.system-prompt:{}}")
    public void setAgentLoopSystemPromptMap(String list) {
        this.agentLoopSystemPromptMap = parseStringMap(list);
    }

    private Map<String, String> agentLoopNextTurnPromptMap = new HashMap<>();
    @Value("${autobots.autoagent.agent-loop.next-turn-prompt:{}}")
    public void setAgentLoopNextTurnPromptMap(String list) {
        this.agentLoopNextTurnPromptMap = parseStringMap(list);
    }

    @Value("${autobots.autoagent.agent-loop.model-name:qwen-vl-max}")
    private String agentLoopModelName;

    @Value("${autobots.autoagent.tool.code_agent.desc:}")
    private String codeAgentDesc;

    @Value("${autobots.autoagent.tool.report_tool.desc:}")
    private String reportToolDesc;

    @Value("${autobots.autoagent.tool.file_tool.desc:}")
    private String fileToolDesc;

    @Value("${autobots.autoagent.tool.deep_search_tool.desc:}")
    private String deepSearchToolDesc;

    @Value("${ai-group.billing.tools.deep-search-microcredits:200000}")
    private long deepSearchMicrocredits;

    @Value("${ai-group.billing.tools.image-generation-microcredits:1000000}")
    private long imageGenerationMicrocredits;

    @Value("${autobots.autoagent.tool.web_fetch_tool.desc:}")
    private String webFetchToolDesc;

    @Value("${autobots.autoagent.tool.multimodalagent_tool.desc:}")
    private String multiModalAgentDesc;

    @Value("${autobots.autoagent.tool.image_generation_tool.desc:}")
    private String imageGenerationToolDesc;

    @Value("${autobots.autoagent.tool.data_analysis_tool.desc:}")
    private String dataAnalysisToolDesc;

    /**
     * codeAgent 配置
     */
    private Map<String, Object> codeAgentParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.code_agent.params:{}}")
    public void setCodeAgentParams(String jsonStr) {
        this.codeAgentParams = parseObjectMap(jsonStr);
    }

    /**
     * reportTool 配置
     */
    private Map<String, Object> reportToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.report_tool.params:{}}")
    public void setReportToolParams(String jsonStr) {
        this.reportToolParams = parseObjectMap(jsonStr);
    }

    /**
     * fileTool 配置
     */
    private Map<String, Object> fileToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.file_tool.params:{}}")
    public void setFileToolParams(String jsonStr) {
        this.fileToolParams = parseObjectMap(jsonStr);
    }

    /**
     * DeepSearchTool 配置
     */
    private Map<String, Object> deepSearchToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.deep_search.params:{}}")
    public void setDeepSearchToolParams(String jsonStr) {
        this.deepSearchToolParams = parseObjectMap(jsonStr);
    }

    /**
     * WebFetchTool 配置
     */
    private Map<String, Object> webFetchToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.web_fetch.params:{}}")
    public void setWebFetchToolParams(String jsonStr) {
        this.webFetchToolParams = parseObjectMap(jsonStr);
    }

    /**
     * MultiModalAgentTool 配置
     */
    private Map<String, Object> multiModalAgentParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.multimodalagent_tool.params:{}}")
    public void setMultiModalAgentParams(String jsonStr) {
        this.multiModalAgentParams = parseObjectMap(jsonStr);
    }

    /**
     * ImageGenerationTool 配置
     */
    private Map<String, Object> imageGenerationToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.image_generation_tool.params:{}}")
    public void setImageGenerationToolParams(String jsonStr) {
        this.imageGenerationToolParams = parseObjectMap(jsonStr);
    }

    /**
     * DataAnalysisTool 配置
     */
    private Map<String, Object> dataAnalysisToolParams = new HashMap<>();
    @Value("${autobots.autoagent.tool.data_analysis_tool.params:{}}")
    public void setDataAnalysisToolParams(String jsonStr) {
        this.dataAnalysisToolParams = parseObjectMap(jsonStr);
    }

    @Value("${autobots.autoagent.tool.file_tool.truncate_len:5000}")
    private Integer fileToolContentTruncateLen;

    /** 单个工具调用的最大尝试次数（含首次）：>1 时对抛异常的瞬时失败做有界重试。默认 1（不重试）。 */
    @Value("${autobots.autoagent.tool.max_attempts:1}")
    private Integer toolMaxAttempts;

    /**
     * 工具暴露模式：all=全部内联，shadow=只观测不裁剪，auto=超过阈值后延迟 MCP 工具，filtered=始终筛选 MCP 工具。
     */
    @Value("${autobots.autoagent.tool.exposure.mode:auto}")
    private String toolExposureMode;

    /** MCP 工具不超过该数量时直接全部暴露，避免小工具集过度增加发现步骤。 */
    @Value("${autobots.autoagent.tool.exposure.max-inline-mcp-tools:8}")
    private Integer toolExposureMaxInlineMcpTools;

    /** 每轮按 query / current task 自动预取的 MCP 工具上限。 */
    @Value("${autobots.autoagent.tool.exposure.max-selected-mcp-tools:6}")
    private Integer toolExposureMaxSelectedMcpTools;

    /** tool_search 未显式传 limit 时的默认返回数量。 */
    @Value("${autobots.autoagent.tool.exposure.search-default-limit:6}")
    private Integer toolExposureSearchDefaultLimit;

    @Value("${autobots.autoagent.tool.deep_search.file_desc.truncate_len:500}")
    private Integer deepSearchToolFileDescTruncateLen;

    @Value("${autobots.autoagent.tool.deep_search.message.truncate_len:500}")
    private Integer deepSearchToolMessageTruncateLen;

    @Value("${autobots.autoagent.deep_search_page_count:3}")
    private String deepSearchPageCount;

    private Map<String, String> multiAgentToolListMap = new HashMap<>();
    @Value("${autobots.autoagent.tool_list:{}}")
    public void setMultiAgentToolList(String list) {
        this.multiAgentToolListMap = parseStringMap(list);
    }

    /**
     * LLM Settings
     */
    private Map<String, LLMSettings> llmSettingsMap;
    @Value("${llm.settings:{}}")
    public void setLLMSettingsMap(String jsonStr) {
        Map<String, LLMSettings> rawSettings = JSON.parseObject(jsonStr, new TypeReference<Map<String, LLMSettings>>() {
        });
        this.llmSettingsMap = normalizeLlmSettingsMap(rawSettings);
    }

    @Value("${autobots.autoagent.agent-loop.max-turns:40}")
    private Integer agentLoopMaxTurns;

    @Value("${autobots.autoagent.agent-loop.max-tool-calls:64}")
    private Integer agentLoopMaxToolCalls;

    @Value("${autobots.autoagent.agent-loop.max-completion-attempts:3}")
    private Integer agentLoopMaxCompletionAttempts;

    @Value("${autobots.autoagent.agent-loop.max-duration-seconds:900}")
    private Long agentLoopMaxDurationSeconds;

    @Value("${autobots.autoagent.agent-loop.max-total-tokens:200000}")
    private Long agentLoopMaxTotalTokens;

    @Value("${autobots.autoagent.agent-loop.max-microcredits:10000000}")
    private Long agentLoopMaxMicrocredits;

    @Value("${autobots.autoagent.executor.max_observe:10000}")
    private String maxObserve;

    @Value("${autobots.autoagent.code_interpreter_url:}")
    private String codeInterpreterUrl;

    @Value("${autobots.autoagent.reactor_tool_token:${AGENT_GROUP_REACTOR_TOOL_TOKEN:${AI_GROUP_INTERNAL_TOKEN:${ai-group.internal.token:}}}}")
    private String reactorToolToken;

    @Value("${autobots.autoagent.deep_search_url:}")
    private String deepSearchUrl;

    @Value("${autobots.autoagent.web_fetch_url:}")
    private String webFetchUrl;

    @Value("${autobots.autoagent.multimodalagent_url:}")
    private String multiModalAgentUrl;

    @Value("${autobots.autoagent.image_generation_url:}")
    private String imageGenerationUrl;

    @Value("${autobots.autoagent.data_analysis_url:}")
    private String dataAnalysisUrl;

    @Value("${autobots.autoagent.session-file-rag.collection:agent_session_file_chunks}")
    private String sessionFileRagCollection;

    @Value("${autobots.autoagent.session-file-rag.chunk-chars:800}")
    private Integer sessionFileRagChunkChars;

    @Value("${autobots.autoagent.session-file-rag.chunk-overlap-chars:120}")
    private Integer sessionFileRagChunkOverlapChars;

    @Value("${autobots.autoagent.session-file-rag.top-k:8}")
    private Integer sessionFileRagTopK;

    @Value("${autobots.autoagent.session-file-rag.score-threshold:0.25}")
    private Float sessionFileRagScoreThreshold;

    // ===== 三层对话记忆配置 =====
    /** 记忆总开关（关闭时仅保留单会话中期记忆的既有行为） */
    @Value("${autobots.autoagent.memory.enabled:true}")
    private Boolean memoryEnabled;

    /** 长期跨会话向量记忆开关（依赖 Qdrant，默认关闭） */
    @Value("${autobots.autoagent.memory.longterm.enabled:false}")
    private Boolean longTermMemoryEnabled;

    /** 长期记忆 Qdrant 集合名 */
    @Value("${autobots.autoagent.memory.longterm.collection:agent_conversation_memory}")
    private String longTermMemoryCollection;

    /** 长期记忆召回条数 */
    @Value("${autobots.autoagent.memory.longterm.top-k:5}")
    private Integer longTermMemoryTopK;

    /** 长期记忆召回相似度阈值 */
    @Value("${autobots.autoagent.memory.longterm.score-threshold:0.6}")
    private Float longTermMemoryScoreThreshold;

    /** 长期记忆时间衰减半衰期（天）：越久的记忆召回权重越低，实现"遗忘" */
    @Value("${autobots.autoagent.memory.longterm.decay-half-life-days:30}")
    private Integer longTermMemoryDecayHalfLifeDays;

    /**
     * skill 脚本默认超时，便于在统一配置快照中查看当前生效值。
     */
    @Value("${autobots.autoagent.skill.default-script-timeout-seconds:120}")
    private Integer skillDefaultScriptTimeoutSeconds;

    /**
     * skill 文本读取上限，便于和 read_tool / skill_tool 的截断行为联动排查。
     */
    @Value("${autobots.autoagent.skill.max-read-chars:12000}")
    private Integer skillMaxReadChars;

    private Map<String, String> sensitivePatterns = new HashMap<>();
    @Value("${autobots.autoagent.sensitive_patterns:{}}")
    public void setSensitivePatterns(String jsonStr) {
        this.sensitivePatterns = parseStringMap(jsonStr);
    }

    private Map<String, String> outputStylePrompts = new HashMap<>();
    @Value("${autobots.autoagent.output_style_prompts:{}}")
    public void setOutputStylePrompts(String jsonStr) {
        this.outputStylePrompts = parseStringMap(jsonStr);
    }

    private Map<String, String> messageInterval = new HashMap<>();
    @Value("${autobots.autoagent.message_interval:{}}")
    public void setMessageInterval(String jsonStr) {
        this.messageInterval = parseStringMap(jsonStr);
    }

    private String structParseToolSystemPrompt = "";
    @Value("${autobots.autoagent.struct_parse_tool_system_prompt:}")
    public void setStructParseToolSystemPrompt(String str) {
        this.structParseToolSystemPrompt = str;
    }

    @Value("${autobots.autoagent.reactor_base_prompt:}")
    private String reactorBasePrompt;

    @Value("${spring.ai.agent.chat.default-role-id:}")
    private String chatDefaultRoleId;

    private static Map<String, String> parseStringMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        return JSON.parseObject(json, new TypeReference<Map<String, String>>() {});
    }

    private static Map<String, Object> parseObjectMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 对 llm.settings 的 key 和 model 字段做规范化，避免配置里混入首尾空格导致模型配置失效。
     */
    private static Map<String, LLMSettings> normalizeLlmSettingsMap(Map<String, LLMSettings> rawSettings) {
        Map<String, LLMSettings> normalizedSettings = new HashMap<>();
        if (rawSettings == null || rawSettings.isEmpty()) {
            return normalizedSettings;
        }

        rawSettings.forEach((modelName, settings) -> {
            if (settings == null) {
                return;
            }

            String normalizedModelName = StringUtils.hasText(modelName) ? modelName.trim() : "";
            if (!StringUtils.hasText(normalizedModelName)) {
                return;
            }

            normalizedSettings.put(normalizedModelName, LLMSettings.builder()
                    .model(StringUtils.hasText(settings.getModel()) ? settings.getModel().trim() : normalizedModelName)
                    .maxTokens(settings.getMaxTokens())
                    .temperature(settings.getTemperature())
                    .apiType(settings.getApiType())
                    .apiKey(settings.getApiKey())
                    .apiVersion(settings.getApiVersion())
                    .baseUrl(settings.getBaseUrl())
                    .interfaceUrl(settings.getInterfaceUrl())
                    .functionCallType(settings.getFunctionCallType())
                    .maxInputTokens(settings.getMaxInputTokens())
                    .extParams(settings.getExtParams())
                    .build());
        });
        return normalizedSettings;
    }

}

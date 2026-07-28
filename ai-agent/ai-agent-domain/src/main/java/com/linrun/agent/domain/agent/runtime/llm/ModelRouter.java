package com.linrun.agent.domain.agent.runtime.llm;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Fixed-priority model routing configured at application startup. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.router.model")
public class ModelRouter implements InitializingBean {

    public enum TaskType { SIMPLE_QA, TOOL_CALLING, REPORT, DEEP_SEARCH }

    private static final Set<String> REPORT_OUTPUT_STYLES = Set.of("html", "docs", "ppt", "report");

    private String simpleQa = "qwen-turbo";
    private String toolCalling = "qwen-plus";
    private String report = "qwen-plus";
    private String deepSearch = "qwen-long";

    public String route(AgentContext context) {
        if (context != null && context.getExecutionProfile() == AgentExecutionProfile.DEEP) {
            return deepSearch;
        }
        if (context != null && REPORT_OUTPUT_STYLES.contains(
                StringUtils.defaultString(context.getOutputStyle()).trim().toLowerCase())) {
            return report;
        }
        if (context != null && (Boolean.TRUE.equals(context.getOnline())
                || (context.getProductFiles() != null && !context.getProductFiles().isEmpty())
                || (context.getTaskProductFiles() != null && !context.getTaskProductFiles().isEmpty()))) {
            return toolCalling;
        }
        return simpleQa;
    }

    public Map<TaskType, String> routingTable() {
        return Map.of(
                TaskType.SIMPLE_QA, simpleQa,
                TaskType.TOOL_CALLING, toolCalling,
                TaskType.REPORT, report,
                TaskType.DEEP_SEARCH, deepSearch);
    }

    @Override
    public void afterPropertiesSet() {
        routingTable().forEach((type, model) -> {
            if (StringUtils.isBlank(model)) {
                throw new IllegalStateException("agent.router.model." + propertyName(type) + " must not be blank");
            }
        });
    }

    private String propertyName(TaskType type) {
        return type.name().toLowerCase().replace('_', '-');
    }
}

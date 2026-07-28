package com.linrun.agent.domain.agent.runtime.deepresearch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReportQualityResult(String status,
                                  List<String> failedSections,
                                  List<String> issues,
                                  double citationCoverage,
                                  int sourceCount,
                                  int charCount) {

    public static ReportQualityResult passed(double citationCoverage, int sourceCount, int charCount) {
        return new ReportQualityResult("PASSED", List.of(), List.of(), citationCoverage, sourceCount, charCount);
    }

    public static ReportQualityResult failed(List<String> failedSections,
                                             List<String> issues,
                                             double citationCoverage,
                                             int sourceCount,
                                             int charCount) {
        return new ReportQualityResult("FAILED", failedSections, issues, citationCoverage, sourceCount, charCount);
    }

    public ReportQualityResult degraded() {
        return new ReportQualityResult("DEGRADED", failedSections, issues, citationCoverage, sourceCount, charCount);
    }

    public boolean passed() {
        return "PASSED".equals(status);
    }

    public boolean requiresRepair() {
        return "FAILED".equals(status);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("failedSections", failedSections);
        map.put("issues", issues);
        map.put("citationCoverage", citationCoverage);
        map.put("sourceCount", sourceCount);
        map.put("charCount", charCount);
        return map;
    }

    public static ReportQualityResult from(Object value) {
        if (value instanceof ReportQualityResult result) {
            return result;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return failed(List.of(), List.of("report has not been reviewed"), 0D, 0, 0);
        }
        return new ReportQualityResult(
                string(map.get("status")),
                DeepResearchState.list(map.get("failedSections")).stream().map(String::valueOf).toList(),
                DeepResearchState.list(map.get("issues")).stream().map(String::valueOf).toList(),
                decimal(map.get("citationCoverage")),
                integer(map.get("sourceCount")),
                integer(map.get("charCount"))
        );
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(string(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(string(value));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }
}

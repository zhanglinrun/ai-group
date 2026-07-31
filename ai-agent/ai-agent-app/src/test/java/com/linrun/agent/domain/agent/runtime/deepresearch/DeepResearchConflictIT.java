package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class DeepResearchConflictIT {

    @Test
    public void shouldBuildARevisionPlanOnlyForReviewerSelectedSubtasks() {
        ResearchPlan plan = ResearchPlan.create("比较留存和定价");
        ResearchPlan revised = plan.revision(List.of(plan.subtasks().getFirst().id()));

        Assert.assertEquals(1, revised.subtasks().size());
        Assert.assertEquals(plan.subtasks().getFirst().id(), revised.subtasks().getFirst().id());
        Assert.assertEquals(1, revised.researcherIndexes().size());
    }

    @Test
    public void shouldKeepDeliveryAndCitationDirectivesOutOfResearchSubtasks() {
        ResearchPlan plan = ResearchPlan.create("深度调研 Java 虚拟线程的 Java 21 状态。"
                + "必须给出至少一个 OpenJDK 或 JEP 的真实 URL，并调用报告工具生成一份短 Markdown 文档。"
                + "[输出格式要求] 最终交付物请调用 report_tool 生成 Markdown 文档报告（fileType=markdown）。");

        Assert.assertEquals(2, plan.subtasks().size());
        Assert.assertTrue(plan.subtasks().stream()
                .allMatch(task -> task.objective().contains("Java 虚拟线程的 Java 21 状态")));
        Assert.assertTrue(plan.subtasks().stream()
                .noneMatch(task -> task.objective().contains("report_tool") || task.objective().contains("真实 URL")));
    }
}

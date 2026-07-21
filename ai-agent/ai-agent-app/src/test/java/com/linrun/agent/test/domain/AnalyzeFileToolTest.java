package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorSaveReq;
import com.linrun.agent.domain.agent.reactor.service.VectorService;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.common.AnalyzeFileTool;
import com.linrun.agent.domain.agent.runtime.tool.common.SessionFileRagService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * analyze_file 分流与会话附件大文本 RAG 单测。
 */
public class AnalyzeFileToolTest {

    @Test
    public void shouldSplitLargeTextIntoOverlappingChunks() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 50; index++) {
            builder.append("段落").append(index).append("：这是用于切块测试的长文本内容。");
        }
        // chunkChars 会被抬到至少 200，构造更长正文确保跨块
        List<String> chunks = SessionFileRagService.splitIntoChunks(builder.toString(), 80, 10);
        Assert.assertTrue("expected multiple chunks, got " + chunks.size(), chunks.size() >= 2);
        Assert.assertTrue(chunks.get(0).length() <= 200);
    }

    @Test
    public void shouldReadSmallTextDirectly() throws Exception {
        FileArtifactPort fileArtifactPort = Mockito.mock(FileArtifactPort.class);
        Mockito.when(fileArtifactPort.readText(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn("这是短文本内容");

        ReactorConfig reactorConfig = new ReactorConfig();
        ReactorRuntimeDependencies dependencies = ReactorRuntimeDependencies.builder()
                .reactorConfig(reactorConfig)
                .fileArtifactPort(fileArtifactPort)
                .build();

        File sessionFile = File.builder()
                .fileName("note.txt")
                .fileSize(20)
                .domainUrl("http://example.com/note.txt")
                .build();

        AnalyzeFileTool tool = new AnalyzeFileTool();
        tool.setAgentContext(AgentContext.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .query("读文件")
                .productFiles(new ArrayList<>(List.of(sessionFile)))
                .runtimeDependencies(dependencies)
                .build());

        Object result = tool.execute(Map.of(
                "fileName", "note.txt",
                "question", "摘要"));
        Assert.assertEquals("这是短文本内容", result);
        Mockito.verify(fileArtifactPort).readText("http://example.com/note.txt", 60L);
    }

    @Test
    public void shouldUseSessionFileRagForLargeText() throws Exception {
        FileArtifactPort fileArtifactPort = Mockito.mock(FileArtifactPort.class);
        StringBuilder largeText = new StringBuilder();
        for (int index = 0; index < 4000; index++) {
            largeText.append("第").append(index).append("句：Agent 需要从大文件中检索关键结论。");
        }
        Mockito.when(fileArtifactPort.readText(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(largeText.toString());

        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any(VectorSaveReq.class))).thenReturn(true);
        Mockito.when(vectorService.vectorRecall(Mockito.any(VectorRecallReq.class)))
                .thenReturn(List.of(Map.of(
                        "text", "Agent 需要从大文件中检索关键结论。",
                        "score", 0.91f,
                        "chunkIndex", "3")));

        ReactorConfig reactorConfig = new ReactorConfig();
        ReflectionTestUtils.setField(reactorConfig, "sessionFileRagCollection", "agent_session_file_chunks");
        ReflectionTestUtils.setField(reactorConfig, "sessionFileRagTopK", 5);
        ReflectionTestUtils.setField(reactorConfig, "sessionFileRagScoreThreshold", 0.2f);
        ReflectionTestUtils.setField(reactorConfig, "sessionFileRagChunkChars", 400);
        ReflectionTestUtils.setField(reactorConfig, "sessionFileRagChunkOverlapChars", 40);

        ReactorRuntimeDependencies dependencies = ReactorRuntimeDependencies.builder()
                .reactorConfig(reactorConfig)
                .fileArtifactPort(fileArtifactPort)
                .vectorService(vectorService)
                .build();

        File sessionFile = File.builder()
                .fileName("report.md")
                .fileSize(300_000)
                .domainUrl("http://example.com/report.md")
                .build();

        AnalyzeFileTool tool = new AnalyzeFileTool();
        tool.setAgentContext(AgentContext.builder()
                .requestId("req-large")
                .sessionId("session-large")
                .query("结论是什么")
                .productFiles(new ArrayList<>(List.of(sessionFile)))
                .runtimeDependencies(dependencies)
                .build());

        Object result = tool.execute(Map.of(
                "fileName", "report.md",
                "question", "关键结论是什么"));

        Assert.assertTrue(String.valueOf(result).contains("session_file_rag"));
        Assert.assertTrue(String.valueOf(result).contains("Agent 需要从大文件中检索关键结论。"));

        ArgumentCaptor<VectorSaveReq> saveCaptor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService).saveVector(saveCaptor.capture());
        Assert.assertEquals("agent_session_file_chunks", saveCaptor.getValue().getCollectionName());
        Assert.assertFalse(saveCaptor.getValue().getDataList().isEmpty());

        ArgumentCaptor<VectorRecallReq> recallCaptor = ArgumentCaptor.forClass(VectorRecallReq.class);
        Mockito.verify(vectorService).vectorRecall(recallCaptor.capture());
        Assert.assertEquals("session-large", recallCaptor.getValue().getKeywordFilterMap().get("sessionId"));
        Assert.assertEquals("report.md", recallCaptor.getValue().getKeywordFilterMap().get("fileName"));
    }
}

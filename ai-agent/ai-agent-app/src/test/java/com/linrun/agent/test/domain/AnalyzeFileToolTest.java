package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestRequest;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestResult;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestRouter;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalHit;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.common.AnalyzeFileTool;
import com.linrun.agent.domain.agent.runtime.tool.common.FileAnalysisResult;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

public class AnalyzeFileToolTest {

    @Test
    public void shouldRouteSmallTextAndReturnDirectContext() throws Exception {
        FileArtifactPort filePort = Mockito.mock(FileArtifactPort.class);
        DocumentIngestRouter router = Mockito.mock(DocumentIngestRouter.class);
        Mockito.when(filePort.readText("https://files/spec.txt", 60L)).thenReturn("small context");
        Mockito.when(router.route(Mockito.any())).thenReturn(DocumentIngestResult.builder()
                .success(true).strategyName("DIRECT_READ").readableText("small context").build());
        AnalyzeFileTool tool = tool(filePort, router, null, "spec.txt");

        FileAnalysisResult result = (FileAnalysisResult) tool.execute(Map.of("fileName", "spec.txt", "question", "summary"));

        Assert.assertEquals("DIRECT_READ", result.strategy());
        Assert.assertEquals("small context", result.answer());
        Assert.assertEquals("spec.txt", result.artifactReference());
        ArgumentCaptor<DocumentIngestRequest> request = ArgumentCaptor.forClass(DocumentIngestRequest.class);
        Mockito.verify(router).route(request.capture());
        Assert.assertEquals("42", request.getValue().getOwnerId());
        Assert.assertEquals("session-1", request.getValue().getConversationId());
        Assert.assertEquals("small context", request.getValue().getContent());
    }

    @Test
    public void shouldRetrieveLargeTextByOwnerAndFileMetadata() throws Exception {
        FileArtifactPort filePort = Mockito.mock(FileArtifactPort.class);
        DocumentIngestRouter router = Mockito.mock(DocumentIngestRouter.class);
        HybridRetriever retriever = Mockito.mock(HybridRetriever.class);
        Mockito.when(filePort.readText("https://files/report.md", 60L)).thenReturn("large content");
        Mockito.when(router.route(Mockito.any())).thenReturn(DocumentIngestResult.builder()
                .success(true).strategyName("CHUNK_EMBED").readableText("chunked").chunkCount(2).build());
        Mockito.when(retriever.retrieve(Mockito.any())).thenReturn(List.of(
                HybridRetrievalHit.builder().content("evidence one").build(),
                HybridRetrievalHit.builder().content("evidence two").build()));
        AnalyzeFileTool tool = tool(filePort, router, retriever, "report.md");

        FileAnalysisResult result = (FileAnalysisResult) tool.execute(
                Map.of("fileName", "report.md", "question", "market trend"));

        Assert.assertTrue(result.answer().contains("evidence one"));
        Assert.assertFalse(result.degraded());
        ArgumentCaptor<HybridRetrievalRequest> request = ArgumentCaptor.forClass(HybridRetrievalRequest.class);
        Mockito.verify(retriever).retrieve(request.capture());
        Assert.assertEquals("42", request.getValue().getOwnerId());
        Assert.assertEquals(List.of("file_chunk"), request.getValue().getDocTypes());
        Assert.assertEquals("report.md", request.getValue().getMetadataFilters().get("fileName"));
    }

    @Test
    public void shouldRouteImageBySecureReferenceWithoutDownloadingOrReturningBase64() throws Exception {
        FileArtifactPort filePort = Mockito.mock(FileArtifactPort.class);
        DocumentIngestRouter router = Mockito.mock(DocumentIngestRouter.class);
        Mockito.when(router.route(Mockito.any())).thenReturn(DocumentIngestResult.builder()
                .success(true).strategyName("VLM_DESCRIBE").readableText("图中显示 2026 年趋势").build());
        AnalyzeFileTool tool = tool(filePort, router, null, "chart.png");

        FileAnalysisResult result = (FileAnalysisResult) tool.execute(
                Map.of("fileName", "chart.png", "question", "图中趋势是什么"));

        Assert.assertEquals("VLM_DESCRIBE", result.strategy());
        Assert.assertTrue(result.uncertainty().contains("verify"));
        Assert.assertFalse(result.answer().contains("base64"));
        Mockito.verify(filePort, Mockito.never()).readText(Mockito.anyString(), Mockito.anyLong());
        ArgumentCaptor<DocumentIngestRequest> request = ArgumentCaptor.forClass(DocumentIngestRequest.class);
        Mockito.verify(router).route(request.capture());
        Assert.assertEquals("https://files/chart.png", request.getValue().getContent());
        Assert.assertEquals("image/png", request.getValue().getMimeType());
    }

    @Test
    public void shouldReturnExplicitVlmDegradedResultWhenVisionProviderFails() throws Exception {
        FileArtifactPort filePort = Mockito.mock(FileArtifactPort.class);
        DocumentIngestRouter router = Mockito.mock(DocumentIngestRouter.class);
        Mockito.when(router.route(Mockito.any())).thenReturn(DocumentIngestResult.builder()
                .success(false).strategyName("VLM_DESCRIBE").errorMessage("provider unavailable").build());
        AnalyzeFileTool tool = tool(filePort, router, null, "chart.png");

        FileAnalysisResult result = (FileAnalysisResult) tool.execute(
                Map.of("fileName", "chart.png", "question", "图中趋势是什么"));

        Assert.assertEquals("VLM_DEGRADED", result.strategy());
        Assert.assertTrue(result.degraded());
        Assert.assertTrue(result.uncertainty().startsWith("high"));
    }

    private AnalyzeFileTool tool(FileArtifactPort filePort,
                                 DocumentIngestRouter router,
                                 HybridRetriever retriever,
                                 String fileName) {
        AgentContext context = AgentContext.builder()
                .ownerId(42L)
                .sessionId("session-1")
                .query("question")
                .productFiles(List.of(File.builder()
                        .fileName(fileName)
                        .domainUrl("https://files/" + fileName)
                        .build()))
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .fileArtifactPort(filePort)
                        .documentIngestRouter(router)
                        .hybridRetriever(retriever)
                        .build())
                .build();
        AnalyzeFileTool tool = new AnalyzeFileTool();
        tool.setAgentContext(context);
        return tool;
    }
}

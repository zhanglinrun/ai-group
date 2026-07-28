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

        Object result = tool.execute(Map.of("fileName", "spec.txt", "question", "summary"));

        Assert.assertEquals("small context", result);
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

        String result = String.valueOf(tool.execute(
                Map.of("fileName", "report.md", "question", "market trend")));

        Assert.assertTrue(result.contains("evidence one"));
        ArgumentCaptor<HybridRetrievalRequest> request = ArgumentCaptor.forClass(HybridRetrievalRequest.class);
        Mockito.verify(retriever).retrieve(request.capture());
        Assert.assertEquals("42", request.getValue().getOwnerId());
        Assert.assertEquals(List.of("file_chunk"), request.getValue().getDocTypes());
        Assert.assertEquals("report.md", request.getValue().getMetadataFilters().get("fileName"));
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

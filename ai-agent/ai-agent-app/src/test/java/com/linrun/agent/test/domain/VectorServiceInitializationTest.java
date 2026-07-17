package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorSaveReq;
import com.linrun.agent.domain.agent.reactor.service.EmbeddingService;
import com.linrun.agent.domain.agent.reactor.service.QdrantService;
import com.linrun.agent.domain.agent.reactor.service.VectorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * VectorService 首次写入建集合、索引和故障恢复测试。
 */
public class VectorServiceInitializationTest {

    @Test
    public void shouldInitializeCollectionFromActualEmbeddingDimensionBeforeUpsert() throws Exception {
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        QdrantService qdrantService = Mockito.mock(QdrantService.class);
        Mockito.when(embeddingService.getVectorBatch(Mockito.anyList()))
                .thenReturn(List.of(List.of(0.1f, 0.2f, 0.3f)));
        VectorService service = service(embeddingService, qdrantService);
        VectorSaveReq req = request("memory", 1);
        req.setKeywordIndexFields(List.of("ownerId", " memoryId ", "ownerId", ""));

        Assert.assertTrue(service.saveVector(req));

        InOrder inOrder = Mockito.inOrder(qdrantService);
        inOrder.verify(qdrantService).ensureCosineCollection(
                "memory", 3, List.of("memoryId", "ownerId"));
        inOrder.verify(qdrantService).upsertVectorsPayloadTrans(
                Mockito.eq("memory"), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    }

    @Test
    public void shouldShareOneInitializationAcrossConcurrentWrites() throws Exception {
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        QdrantService qdrantService = Mockito.mock(QdrantService.class);
        Mockito.when(embeddingService.getVectorBatch(Mockito.anyList()))
                .thenReturn(List.of(List.of(0.1f, 0.2f, 0.3f)));
        CountDownLatch initializerEntered = new CountDownLatch(1);
        CountDownLatch releaseInitializer = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            initializerEntered.countDown();
            if (!releaseInitializer.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test initializer was not released");
            }
            return null;
        }).when(qdrantService).ensureCosineCollection(
                Mockito.eq("memory"), Mockito.eq(3), Mockito.eq(List.of("ownerId")));
        VectorService service = service(embeddingService, qdrantService);
        VectorSaveReq req = request("memory", 1);
        req.setKeywordIndexFields(List.of("ownerId"));

        int writers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.saveVector(req);
                }));
            }
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Assert.assertTrue(initializerEntered.await(5, TimeUnit.SECONDS));
            releaseInitializer.countDown();
            for (Future<Boolean> future : futures) {
                Assert.assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            releaseInitializer.countDown();
            executor.shutdownNow();
        }

        Mockito.verify(qdrantService, Mockito.times(1)).ensureCosineCollection(
                "memory", 3, List.of("ownerId"));
        Mockito.verify(qdrantService, Mockito.times(writers)).upsertVectorsPayloadTrans(
                Mockito.eq("memory"), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    }

    @Test
    public void shouldInvalidateInitializationAndRetryAfterUpsertFailure() throws Exception {
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        QdrantService qdrantService = Mockito.mock(QdrantService.class);
        Mockito.when(embeddingService.getVectorBatch(Mockito.anyList()))
                .thenReturn(List.of(List.of(0.1f, 0.2f, 0.3f)));
        Mockito.doThrow(new RuntimeException("collection disappeared"))
                .doReturn(null)
                .when(qdrantService)
                .upsertVectorsPayloadTrans(Mockito.eq("memory"), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
        VectorService service = service(embeddingService, qdrantService);
        VectorSaveReq req = request("memory", 1);
        req.setKeywordIndexFields(List.of("ownerId"));

        Assert.assertTrue(service.saveVector(req));

        Mockito.verify(qdrantService, Mockito.times(2)).ensureCosineCollection(
                "memory", 3, List.of("ownerId"));
        Mockito.verify(qdrantService, Mockito.times(2)).upsertVectorsPayloadTrans(
                Mockito.eq("memory"), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    }

    @Test
    public void shouldRetryInitializationOnNextSaveAfterInitializationFailure() throws Exception {
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        QdrantService qdrantService = Mockito.mock(QdrantService.class);
        Mockito.when(embeddingService.getVectorBatch(Mockito.anyList()))
                .thenReturn(List.of(List.of(0.1f, 0.2f, 0.3f)));
        Mockito.doThrow(new RuntimeException("qdrant unavailable"))
                .doNothing()
                .when(qdrantService)
                .ensureCosineCollection("memory", 3, List.of("ownerId"));
        VectorService service = service(embeddingService, qdrantService);
        VectorSaveReq req = request("memory", 1);
        req.setKeywordIndexFields(List.of("ownerId"));

        Assert.assertFalse(service.saveVector(req));
        Assert.assertTrue(service.saveVector(req));

        Mockito.verify(qdrantService, Mockito.times(2)).ensureCosineCollection(
                "memory", 3, List.of("ownerId"));
        Mockito.verify(qdrantService, Mockito.times(1)).upsertVectorsPayloadTrans(
                Mockito.eq("memory"), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    }

    @Test
    public void shouldRejectInconsistentEmbeddingDimensionsBeforeQdrantMutation() {
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        QdrantService qdrantService = Mockito.mock(QdrantService.class);
        Mockito.when(embeddingService.getVectorBatch(Mockito.anyList())).thenReturn(List.of(
                List.of(0.1f, 0.2f),
                List.of(0.3f, 0.4f, 0.5f)
        ));
        VectorService service = service(embeddingService, qdrantService);

        Assert.assertFalse(service.saveVector(request("memory", 2)));
        Mockito.verifyNoInteractions(qdrantService);
    }

    private VectorService service(EmbeddingService embeddingService, QdrantService qdrantService) {
        VectorService service = new VectorService();
        service.setEmbeddingService(embeddingService);
        service.setQdrantService(qdrantService);
        return service;
    }

    private VectorSaveReq request(String collectionName, int dataCount) {
        VectorSaveReq req = new VectorSaveReq();
        req.setCollectionName(collectionName);
        List<VectorSaveReq.VectorData> dataList = new ArrayList<>();
        for (int i = 0; i < dataCount; i++) {
            VectorSaveReq.VectorData data = new VectorSaveReq.VectorData();
            data.setEmbeddingText("text-" + i);
            data.setPayloads(new LinkedHashMap<>(Map.of("ownerId", "user-1")));
            dataList.add(data);
        }
        req.setDataList(dataList);
        return req;
    }
}

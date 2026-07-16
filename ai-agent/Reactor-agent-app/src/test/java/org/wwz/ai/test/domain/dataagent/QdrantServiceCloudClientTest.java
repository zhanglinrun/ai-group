package org.wwz.ai.test.domain.dataagent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.reactor.config.data.DataAgentConfig;
import org.wwz.ai.domain.agent.reactor.config.data.QdrantConfig;
import org.wwz.ai.domain.agent.reactor.service.QdrantService;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant 云端地址解析测试。
 */
public class QdrantServiceCloudClientTest {

    @Test
    public void shouldResolveHttpsUrlToTlsEndpoint() {
        QdrantConfig qdrantConfig = new QdrantConfig();
        qdrantConfig.setUrl("https://cluster.qdrant.cloud");
        qdrantConfig.setPort(6334);
        qdrantConfig.setApiKey("key");
        qdrantConfig.setPreferGrpc(true);
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        dataAgentConfig.setQdrantConfig(qdrantConfig);

        QdrantService service = new QdrantService();
        service.setDataAgentConfig(dataAgentConfig);

        QdrantService.ResolvedQdrantEndpoint endpoint = service.resolveEndpoint(qdrantConfig);
        Assert.assertEquals("cluster.qdrant.cloud", endpoint.getHost());
        Assert.assertEquals(6334, endpoint.getPort());
        Assert.assertTrue(endpoint.isTlsEnabled());
        Assert.assertEquals("key", endpoint.getApiKey());
        Assert.assertTrue(endpoint.isPreferGrpc());
    }

    @Test
    public void shouldKeepLegacyHostPortMode() {
        QdrantConfig qdrantConfig = new QdrantConfig();
        qdrantConfig.setHost("127.0.0.1");
        qdrantConfig.setPort(6334);
        qdrantConfig.setPreferGrpc(false);
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        dataAgentConfig.setQdrantConfig(qdrantConfig);

        QdrantService service = new QdrantService();
        service.setDataAgentConfig(dataAgentConfig);

        QdrantService.ResolvedQdrantEndpoint endpoint = service.resolveEndpoint(qdrantConfig);
        Assert.assertEquals("127.0.0.1", endpoint.getHost());
        Assert.assertEquals(6334, endpoint.getPort());
        Assert.assertFalse(endpoint.isTlsEnabled());
        Assert.assertFalse(endpoint.isPreferGrpc());
    }

    @Test
    public void shouldCreateRestCollectionAndKeywordIndexesWhenMissing() throws Exception {
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        Mockito.when(remoteHttpPort.execute(Mockito.any())).thenAnswer(invocation -> {
            RemoteHttpRequest request = invocation.getArgument(0);
            requests.add(request);
            if ("GET".equals(request.getMethod()) && request.getUrl().endsWith("/collections")) {
                return "{\"result\":{\"collections\":[]}}";
            }
            if ("PUT".equals(request.getMethod())) {
                return "{\"result\":true,\"status\":\"ok\"}";
            }
            throw new AssertionError("unexpected request: " + request.getMethod() + " " + request.getUrl());
        });
        QdrantService service = restService(remoteHttpPort);

        service.ensureCosineCollection("memory", 1024, List.of("ownerId", "memoryId"));

        List<RemoteHttpRequest> collectionCreates = requests.stream()
                .filter(request -> "PUT".equals(request.getMethod()))
                .filter(request -> request.getUrl().endsWith("/collections/memory"))
                .toList();
        Assert.assertEquals(1, collectionCreates.size());
        JSONObject createBody = JSON.parseObject(collectionCreates.get(0).getBody());
        Assert.assertEquals(1024, createBody.getJSONObject("vectors").getIntValue("size"));
        Assert.assertEquals("Cosine", createBody.getJSONObject("vectors").getString("distance"));

        List<RemoteHttpRequest> indexCreates = requests.stream()
                .filter(request -> "PUT".equals(request.getMethod()))
                .filter(request -> request.getUrl().contains("/index?wait=true"))
                .toList();
        Assert.assertEquals(2, indexCreates.size());
        Assert.assertEquals(
                List.of("memoryId", "ownerId"),
                indexCreates.stream()
                        .map(request -> JSON.parseObject(request.getBody()).getString("field_name"))
                        .toList());
        Assert.assertTrue(indexCreates.stream().allMatch(request ->
                "keyword".equals(JSON.parseObject(request.getBody()).getString("field_schema"))));
        Assert.assertTrue(requests.stream().noneMatch(request -> "DELETE".equals(request.getMethod())));
    }

    @Test
    public void shouldReuseMatchingRestCollectionAndExistingIndexes() throws Exception {
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        Mockito.when(remoteHttpPort.execute(Mockito.any())).thenAnswer(invocation -> {
            RemoteHttpRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.getUrl().endsWith("/collections")) {
                return "{\"result\":{\"collections\":[{\"name\":\"memory\"}]}}";
            }
            if (request.getUrl().endsWith("/collections/memory")) {
                return collectionState(1024, "ownerId", "memoryId");
            }
            throw new AssertionError("unexpected request: " + request.getMethod() + " " + request.getUrl());
        });
        QdrantService service = restService(remoteHttpPort);

        service.ensureCosineCollection("memory", 1024, List.of("ownerId", "memoryId", "ownerId"));

        Assert.assertEquals(2, requests.size());
        Assert.assertTrue(requests.stream().allMatch(request -> "GET".equals(request.getMethod())));
        Assert.assertTrue(requests.stream().noneMatch(request -> "DELETE".equals(request.getMethod())));
    }

    @Test
    public void shouldRejectRestDimensionMismatchWithoutDeletingCollection() throws Exception {
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        Mockito.when(remoteHttpPort.execute(Mockito.any())).thenAnswer(invocation -> {
            RemoteHttpRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.getUrl().endsWith("/collections")) {
                return "{\"result\":{\"collections\":[{\"name\":\"memory\"}]}}";
            }
            if (request.getUrl().endsWith("/collections/memory")) {
                return collectionState(1536, "ownerId");
            }
            throw new AssertionError("unexpected request: " + request.getMethod() + " " + request.getUrl());
        });
        QdrantService service = restService(remoteHttpPort);

        IllegalStateException error = Assert.assertThrows(IllegalStateException.class,
                () -> service.ensureCosineCollection("memory", 1024, List.of("ownerId")));

        Assert.assertTrue(error.getMessage().contains("dimension mismatch"));
        Assert.assertTrue(requests.stream().noneMatch(request -> "DELETE".equals(request.getMethod())));
        Assert.assertTrue(requests.stream().noneMatch(request -> "PUT".equals(request.getMethod())));
    }

    private QdrantService restService(RemoteHttpPort remoteHttpPort) {
        QdrantConfig qdrantConfig = new QdrantConfig();
        qdrantConfig.setUrl("http://qdrant.local:6333");
        qdrantConfig.setPort(6333);
        qdrantConfig.setPreferGrpc(false);
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        dataAgentConfig.setQdrantConfig(qdrantConfig);

        QdrantService service = new QdrantService();
        service.setDataAgentConfig(dataAgentConfig);
        ReflectionTestUtils.setField(service, "remoteHttpPort", remoteHttpPort);
        return service;
    }

    private String collectionState(int dimension, String... indexedFields) {
        JSONObject payloadSchema = new JSONObject();
        for (String field : indexedFields) {
            payloadSchema.put(field, JSON.parseObject("{\"data_type\":\"keyword\"}"));
        }
        JSONObject vectors = new JSONObject();
        vectors.put("size", dimension);
        JSONObject params = new JSONObject();
        params.put("vectors", vectors);
        JSONObject config = new JSONObject();
        config.put("params", params);
        JSONObject result = new JSONObject();
        result.put("config", config);
        result.put("payload_schema", payloadSchema);
        JSONObject response = new JSONObject();
        response.put("result", result);
        return response.toJSONString();
    }
}

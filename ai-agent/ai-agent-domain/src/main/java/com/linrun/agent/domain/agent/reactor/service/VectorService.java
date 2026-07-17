package com.linrun.agent.domain.agent.reactor.service;


import com.linrun.agent.domain.agent.reactor.data.dto.VectorModelSchema;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorSaveReq;
import com.linrun.agent.domain.agent.ledger.entity.ChatModelSchema;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.runtime.executor.AgentExecutorSupport;
import com.linrun.agent.types.agent.config.AgentExecutorNames;

import jakarta.annotation.Resource;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.qdrant.client.ConditionFactory.*;


@Service
@Slf4j
public class VectorService {

    private EmbeddingService embeddingService;
    private QdrantService qdrantService;
    private final Set<CollectionInitializationKey> initializedCollections = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Object> collectionInitializationLocks = new ConcurrentHashMap<>();

    @Resource(name = AgentExecutorNames.TOOL_EXECUTOR)
    private Executor toolExecutor;

    @Autowired
    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Autowired
    public void setQdrantService(QdrantService qdrantService) {
        this.qdrantService = qdrantService;
    }


    public List<Map<String, Object>> vectorRecall(VectorRecallReq req) {
        if (StringUtils.isBlank(req.getCollectionName())) {
            throw new RuntimeException("集合名称为空！");
        }
        if (StringUtils.isBlank(req.getQuery())) {
            throw new RuntimeException("查询query为空！");
        }

        CompletableFuture<List<Map<String, Object>>> future = null;
        try {
            future = AgentExecutorSupport.supplyAsync(toolExecutor, "vectorRecall", () -> recall(req));
            future.exceptionally(throwable -> null);
            List<Map<String, Object>> maps = future.get(req.getTimeout(), TimeUnit.MILLISECONDS);
            if (maps == null || maps.isEmpty()) {
                log.warn("vector recall returned empty limit={} filterCount={} timeoutMs={}",
                        req.getLimit(), req.getKeywordFilterMap() == null ? 0 : req.getKeywordFilterMap().size(),
                        req.getTimeout());
                return new ArrayList<>();
            }
            return maps;
        } catch (Exception e) {
            log.error("vector recall failed limit={} filterCount={} timeoutMs={} errorType={}",
                    req.getLimit(), req.getKeywordFilterMap() == null ? 0 : req.getKeywordFilterMap().size(),
                    req.getTimeout(), e.getClass().getSimpleName());
            if (future != null) {
                try {
                    future.cancel(true);
                } catch (Exception e1) {
                    log.error("vector recall cancellation failed errorType={}",
                            e1.getClass().getSimpleName());
                }
            }
        }

        return new ArrayList<>();
    }

    private List<Map<String, Object>> recall(VectorRecallReq req) {
        try {
            List<Float> vector = embeddingService.getVector(req.getQuery());
            if (CollectionUtils.isEmpty(vector)) {
                log.error("vector recall embedding empty queryChars={} filterCount={}",
                        req.getQuery() == null ? 0 : req.getQuery().length(),
                        req.getKeywordFilterMap() == null ? 0 : req.getKeywordFilterMap().size());
                throw new RuntimeException("向量生成失败！");
            }

            Points.Filter filter = null;
            if (Objects.nonNull(req.getKeywordFilterMap()) && !req.getKeywordFilterMap().isEmpty()) {
                Points.Filter.Builder filterBuilder = Points.Filter.newBuilder();
                req.getKeywordFilterMap().forEach((k, v) -> {
                    if (v instanceof String) {
                        filterBuilder.addMust(matchKeyword(k, (String) v));
                    } else if (v instanceof Long) {
                        filterBuilder.addMust(match(k, (long) v));
                    } else if (v instanceof Integer) {
                        filterBuilder.addMust(match(k, (int) v));
                    } else if (v instanceof Boolean) {
                        filterBuilder.addMust(match(k, (boolean) v));
                    } else if (v instanceof List) {
                        List<Object> list = (List<Object>) v;
                        if (CollectionUtils.isNotEmpty(list)) {
                            Object type = list.get(0);
                            if (type instanceof String) {
                                filterBuilder.addMust(matchKeywords(k, (List<String>) v));
                            } else if (type instanceof Long || type instanceof Integer) {
                                filterBuilder.addMust(matchValues(k, (List<Long>) v));
                            }
                        }
                    }
                });
                filter = filterBuilder.build();
            }

            List<Points.ScoredPoint> scoredPoints = qdrantService.search(req.getCollectionName(), vector, req.getLimit(), filter, req.getPayloads(), req.getTimeout(), TimeUnit.MILLISECONDS, req.getScoreThreshold());
            return scoredPoints.stream().map(p -> {
                Map<String, Object> hashMap = new HashMap<>();
                Map<String, JsonWithInt.Value> payloadMap = p.getPayloadMap();
                payloadMap.forEach((k, v) -> hashMap.put(k, v.getStringValue()));
                hashMap.put("score", p.getScore());
                hashMap.put("_id", p.getId().getUuid());
                return hashMap;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("vector recall execution failed limit={} filterCount={} errorType={}",
                    req.getLimit(), req.getKeywordFilterMap() == null ? 0 : req.getKeywordFilterMap().size(),
                    e.getClass().getSimpleName());
            throw new RuntimeException(e);
        }
    }

    public Boolean saveVector(VectorSaveReq vectorSaveReq) {
        try {
            if (vectorSaveReq == null) {
                throw new IllegalArgumentException("vectorSaveReq is null!");
            }
            if (StringUtils.isBlank(vectorSaveReq.getCollectionName())) {
                throw new RuntimeException("collectionName is null!");
            }
            if (CollectionUtils.isEmpty(vectorSaveReq.getDataList())) {
                throw new RuntimeException("dataList is null!");
            }

            List<String> textList = vectorSaveReq.getDataList().stream().map(VectorSaveReq.VectorData::getEmbeddingText).collect(Collectors.toList());
            List<List<Float>> vectors = embeddingService.getVectorBatch(textList);
            int dimension = validateEmbeddingBatch(vectors, vectorSaveReq.getDataList().size());
            List<String> idList = vectorSaveReq.getDataList().stream().map(data -> {
                if (StringUtils.isNotBlank(data.getUuid()) && isUuid(data.getUuid())) {
                    return data.getUuid();
                }
                return UUID.randomUUID().toString();
            }).collect(Collectors.toList());
            List<Map<String, Object>> payloads = vectorSaveReq.getDataList().stream().map(VectorSaveReq.VectorData::getPayloads).collect(Collectors.toList());
            CollectionInitializationKey initializationKey = new CollectionInitializationKey(
                    vectorSaveReq.getCollectionName(),
                    dimension,
                    normalizeKeywordIndexFields(vectorSaveReq.getKeywordIndexFields()));
            ensureCollectionInitialized(initializationKey);
            upsertWithInitializationRecovery(initializationKey, idList, vectors, payloads);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("vector save interrupted dataCount={}",
                    vectorSaveReq == null || vectorSaveReq.getDataList() == null
                            ? 0 : vectorSaveReq.getDataList().size());
            return false;
        } catch (Exception e) {
            log.error("vector save failed dataCount={} errorType={}",
                    vectorSaveReq == null || vectorSaveReq.getDataList() == null
                            ? 0 : vectorSaveReq.getDataList().size(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private void ensureCollectionInitialized(CollectionInitializationKey key) throws Exception {
        if (initializedCollections.contains(key)) {
            return;
        }
        Object lock = collectionInitializationLocks.computeIfAbsent(key.collectionName(), ignored -> new Object());
        synchronized (lock) {
            if (initializedCollections.contains(key)) {
                return;
            }
            qdrantService.ensureCosineCollection(
                    key.collectionName(), key.dimension(), key.keywordIndexFields());
            initializedCollections.add(key);
        }
    }

    private void upsertWithInitializationRecovery(CollectionInitializationKey key,
                                                  List<String> idList,
                                                  List<List<Float>> vectors,
                                                  List<Map<String, Object>> payloads) throws Exception {
        try {
            qdrantService.upsertVectorsPayloadTrans(key.collectionName(), idList, vectors, payloads);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception firstFailure) {
            // 集合可能在进程运行期间被重建/删除。清理本地就绪标记后重新探测一次，避免永久假就绪。
            initializedCollections.remove(key);
            log.warn("vector upsert failed after cached initialization; retry collection initialization collection={} errorType={}",
                    key.collectionName(), firstFailure.getClass().getSimpleName());
            try {
                ensureCollectionInitialized(key);
                qdrantService.upsertVectorsPayloadTrans(key.collectionName(), idList, vectors, payloads);
            } catch (Exception retryFailure) {
                if (retryFailure != firstFailure) {
                    retryFailure.addSuppressed(firstFailure);
                }
                throw retryFailure;
            }
        }
    }

    private int validateEmbeddingBatch(List<List<Float>> vectors, int expectedCount) {
        if (CollectionUtils.isEmpty(vectors) || vectors.size() != expectedCount) {
            throw new IllegalStateException("embedding vector count does not match input count");
        }
        int dimension = -1;
        for (List<Float> vector : vectors) {
            if (CollectionUtils.isEmpty(vector)) {
                throw new IllegalStateException("embedding vector is empty");
            }
            if (dimension < 0) {
                dimension = vector.size();
            } else if (dimension != vector.size()) {
                throw new IllegalStateException("embedding vectors have inconsistent dimensions");
            }
            if (vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new IllegalStateException("embedding vector contains non-finite value");
            }
        }
        return dimension;
    }

    private List<String> normalizeKeywordIndexFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    public Boolean deleteVector(String collectionName, List<String> vectorIdList) {
        if (StringUtils.isBlank(collectionName)) {
            throw new RuntimeException("collectionName is null!");
        }
        if (CollectionUtils.isEmpty(vectorIdList)) {
            throw new RuntimeException("vectorIdList is null!");
        }

        try {
            List<Points.PointId> pointIds = vectorIdList.stream().map(vId -> PointIdFactory.id(UUID.fromString(vId))).collect(Collectors.toList());
            qdrantService.deletePointsSync(collectionName, pointIds);
            return true;
        } catch (Exception e) {
            log.error("vector delete by id failed idCount={} errorType={}",
                    vectorIdList.size(), e.getClass().getSimpleName());
            return false;
        }
    }

    public Boolean deleteVector(String collectionName, Points.Filter filter) {
        if (StringUtils.isBlank(collectionName)) {
            throw new RuntimeException("collectionName is null!");
        }
        if (filter == null) {
            throw new RuntimeException("filter is null!");
        }
        try {
            qdrantService.deleteByFilterSync(collectionName, filter);
            return true;
        } catch (Exception e) {
            log.error("vector delete by filter failed errorType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isUuid(String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record CollectionInitializationKey(String collectionName,
                                               int dimension,
                                               List<String> keywordIndexFields) {

        private CollectionInitializationKey {
            keywordIndexFields = List.copyOf(keywordIndexFields);
        }
    }
}

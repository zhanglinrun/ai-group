package com.linrun.agent.domain.agent.rag.ingest;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 切分+向量化策略：大文本（> 阈值）切分后逐块 embedding 入 pgvector。
 *
 * <p>用 Spring AI 的 {@link TokenTextSplitter} 替代 Python 侧 document/splitter.py，
 * 按 token 边界切分避免截断词义。每块独立向量化、独立可检索。</p>
 */
@Slf4j
@Component
@ConditionalOnBean(PgVectorMemoryRepository.class)
public class ChunkEmbedStrategy implements IngestStrategy {

    private static final int CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 200;
    private static final int MIN_CHUNK_SIZE_CHARS = 350;
    private static final int MAX_NUM_CHUNKS = 10000;
    private static final boolean KEEP_SEPARATOR = true;

    private final PgVectorMemoryRepository memoryRepository;
    private final TokenTextSplitter textSplitter;

    public ChunkEmbedStrategy(PgVectorMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
                .withMaxNumChunks(MAX_NUM_CHUNKS)
                .withKeepSeparator(KEEP_SEPARATOR)
                .build();
    }

    @Override
    public boolean supports(DocumentIngestRequest request) {
        if (request == null || StringUtils.isBlank(request.getContent())) {
            return false;
        }
        return DocumentIngestRouter.isTextMime(request.getMimeType())
                && request.getContent().length() > DocumentIngestRouter.directReadThreshold();
    }

    @Override
    public DocumentIngestResult ingest(DocumentIngestRequest request) {
        String content = StringUtils.defaultString(request.getContent()).trim();
        List<Document> chunks = textSplitter.apply(List.of(new Document(content)));
        log.info("chunk embed ingest ownerId={} fileName={} chars={} chunks={}",
                request.getOwnerId(), request.getFileName(), content.length(), chunks.size());

        List<String> memoryIds = new ArrayList<>();
        List<String> chunkTexts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i).getText();
            if (StringUtils.isBlank(chunkText)) {
                continue;
            }
            String memoryId = UUID.nameUUIDFromBytes(
                    (request.getOwnerId() + "|" + request.getFileName() + "|" + i)
                            .getBytes()).toString();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fileName", StringUtils.defaultString(request.getFileName()));
            metadata.put("chunkIndex", i);
            metadata.put("chunkTotal", chunks.size());
            boolean ok = memoryRepository.saveMemory(
                    memoryId, request.getOwnerId(), "file_chunk",
                    chunkText, metadata, request.getConversationId());
            if (ok) {
                memoryIds.add(memoryId);
                chunkTexts.add(chunkText);
            }
        }
        String readableText = String.join("\n\n---\n\n", chunkTexts);
        return DocumentIngestResult.builder()
                .strategyName("CHUNK_EMBED")
                .success(!memoryIds.isEmpty())
                .memoryIds(memoryIds)
                .readableText(readableText)
                .chunkCount(memoryIds.size())
                .build();
    }
}

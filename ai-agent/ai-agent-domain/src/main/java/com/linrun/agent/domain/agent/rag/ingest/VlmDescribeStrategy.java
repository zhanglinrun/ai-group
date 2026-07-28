package com.linrun.agent.domain.agent.rag.ingest;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VLM 描述策略：图片走多模态模型生成文字描述，存描述不存图。
 *
 * <p>借鉴 dodo-agentx 的 VlmDescribeStrategy。设计权衡：图片无法直接向量化检索，
 * 先用 VLM 生成结构化描述（"这是一张销售柱状图，2025年Q3环比增长15%..."），
 * 再把描述当普通文本入库，既可向量检索也可关键词检索。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class VlmDescribeStrategy implements IngestStrategy {

    private static final String VLM_PROMPT = "请详细描述这张图片的内容，包括图表数据、文字、场景等关键信息，用于后续检索。用中文回答。";

    private final PgVectorMemoryRepository memoryRepository;
    private final ChatModel chatModel;
    private final String visionModelName;

    @Autowired
    public VlmDescribeStrategy(PgVectorMemoryRepository memoryRepository,
                                ChatModel chatModel,
                                @Value("${agent.rag.vlm.model:qwen-vl-plus}") String visionModelName) {
        this.memoryRepository = memoryRepository;
        this.chatModel = chatModel;
        this.visionModelName = visionModelName;
    }

    @Override
    public boolean supports(DocumentIngestRequest request) {
        if (request == null) {
            return false;
        }
        return DocumentIngestRouter.isImageMime(request.getMimeType());
    }

    @Override
    public DocumentIngestResult ingest(DocumentIngestRequest request) {
        String imageData = StringUtils.defaultIfBlank(request.getBase64Data(), request.getContent());
        if (StringUtils.isBlank(imageData)) {
            return DocumentIngestResult.builder()
                    .strategyName("VLM_DESCRIBE")
                    .success(false)
                    .errorMessage("image data is blank")
                    .build();
        }
        try {
            Media imageMedia = buildImageMedia(request.getMimeType(), imageData);
            UserMessage userMessage = UserMessage.builder()
                    .text(VLM_PROMPT)
                    .media(imageMedia)
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(visionModelName).build();
            ChatResponse response = chatModel.call(new Prompt(List.of(userMessage), options));
            String description = response.getResult().getOutput().getText();
            if (StringUtils.isBlank(description)) {
                return DocumentIngestResult.builder()
                        .strategyName("VLM_DESCRIBE")
                        .success(false)
                        .errorMessage("VLM returned empty description")
                        .build();
            }
            String memoryId = UUID.nameUUIDFromBytes(
                    (request.getOwnerId() + "|vlm|" + request.getFileName())
                            .getBytes()).toString();
            Map<String, Object> metadata = Map.of(
                    "fileName", StringUtils.defaultString(request.getFileName()),
                    "sourceType", "image",
                    "mimeType", StringUtils.defaultString(request.getMimeType()));
            boolean saved = memoryRepository.saveMemory(
                    memoryId, request.getOwnerId(), "image_description",
                    description, metadata, request.getConversationId());
            log.info("vlm describe ingest ownerId={} fileName={} descChars={} saved={}",
                    request.getOwnerId(), request.getFileName(), description.length(), saved);
            return DocumentIngestResult.builder()
                    .strategyName("VLM_DESCRIBE")
                    .success(saved)
                    .memoryIds(saved ? List.of(memoryId) : List.of())
                    .readableText(description)
                    .chunkCount(1)
                    .build();
        } catch (Exception e) {
            log.warn("vlm describe failed ownerId={} fileName={} errorType={}",
                    request.getOwnerId(), request.getFileName(), e.getClass().getSimpleName(), e);
            return DocumentIngestResult.builder()
                    .strategyName("VLM_DESCRIBE")
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Media buildImageMedia(String mimeType, String imageData) {
        String mime = StringUtils.defaultIfBlank(mimeType, "image/png");
        MimeType parsedMime = MimeTypeUtils.parseMimeType(mime);
        String normalized = imageData.trim();
        if (normalized.startsWith("data:")) {
            int commaIndex = normalized.indexOf(',');
            String metadata = commaIndex > 0 ? normalized.substring(5, commaIndex) : "";
            String mimeTypeValue = metadata.split(";")[0];
            if (StringUtils.isNotBlank(mimeTypeValue)) {
                parsedMime = MimeType.valueOf(mimeTypeValue);
            }
            normalized = commaIndex > 0 ? normalized.substring(commaIndex + 1) : normalized;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            try {
                return new Media(parsedMime, new org.springframework.core.io.UrlResource(normalized));
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("invalid image url: " + normalized, e);
            }
        }
        byte[] data = Base64.getDecoder().decode(normalized);
        return new Media(parsedMime, new ByteArrayResource(data));
    }
}

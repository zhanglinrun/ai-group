package com.linrun.agent.domain.agent.rag.memory;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像记忆服务：管理 {@code agent_user_profile} 表的偏好/事实 key-value。
 *
 * <p>对应 dodo-agentx 的用户画像层。与三层语义记忆（qa_pair/session/cross）职责分离：
 * <ul>
 *   <li>语义记忆 —— 对话衍生的向量记忆，可检索、有 TTL</li>
 *   <li>用户画像 —— 显式声明的 key-value（"我叫张三" → fact:user-name=张三），无向量、无 TTL、可精确查询</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class UserProfileMemoryService {

    private final PgVectorMemoryRepository memoryRepository;

    @Autowired
    public UserProfileMemoryService(PgVectorMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /**
     * 写入/更新一条画像记忆（按 owner_id + memory_key upsert）。
     */
    public boolean save(String ownerId, String memoryKey, String memoryType,
                        String content, double confidence, String source) {
        return memoryRepository.saveUserProfile(
                ownerId, memoryKey, memoryType, content, confidence, source);
    }

    /**
     * 查询用户全部画像，按 memory_key → content 组织成 map（供 LLM 上下文注入）。
     */
    public Map<String, String> getProfileMap(String ownerId) {
        List<Map<String, Object>> rows = memoryRepository.getUserProfile(ownerId);
        Map<String, String> profile = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("memory_key"));
            String content = String.valueOf(row.get("content"));
            if (StringUtils.isNotBlank(key)) {
                profile.put(key, content);
            }
        }
        return profile;
    }

    /**
     * 删除指定画像 key。
     */
    public boolean delete(String ownerId, String memoryKey) {
        return memoryRepository.deleteUserProfileKey(ownerId, memoryKey);
    }

    /**
     * 把画像 map 格式化为可注入 LLM 上下文的文本。
     */
    public String formatForContext(String ownerId) {
        Map<String, String> profile = getProfileMap(ownerId);
        if (profile.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【用户画像】\n");
        profile.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}

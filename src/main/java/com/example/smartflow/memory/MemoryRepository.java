package com.example.smartflow.memory;

import com.example.smartflow.flow.MemoryFact;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 滚动摘要（flow_memory_summary）和长期记忆（flow_user_memory）的存取。
 * SQL 在 {@link MemoryMapper}（resources/mapper/MemoryMapper.xml），对外方法保持不变。
 */
@Repository
public class MemoryRepository {

    private final MemoryMapper memoryMapper;

    public MemoryRepository(MemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    // ------------------------------------------------------------------ 滚动摘要

    /**
     * @param summary         摘要内容
     * @param upToMessageId   已压缩进摘要的最后一条消息 id，没有摘要时为 0
     * @param summarizedCount 累计压缩了多少条消息
     */
    public record SummaryRecord(String summary, long upToMessageId, int summarizedCount) {
        public static final SummaryRecord EMPTY = new SummaryRecord("", 0L, 0);
    }

    public SummaryRecord findSummary(String sessionId) {
        SummaryRecord record = memoryMapper.findSummary(sessionId);
        return record == null ? SummaryRecord.EMPTY : record;
    }

    public void saveSummary(String sessionId, SummaryRecord record) {
        memoryMapper.upsertSummary(sessionId, record);
    }

    // ------------------------------------------------------------------ 长期记忆

    public List<MemoryFact> listUserMemories(String userId, int limit) {
        return memoryMapper.listUserMemories(userId, limit);
    }

    /** 同一用户同一个 key 只保留一条，新值覆盖旧值 */
    public void upsertUserMemory(String userId, String key, String value, String sessionId, String turnId) {
        memoryMapper.upsertUserMemory(userId, key, value, sessionId, turnId);
    }

    public void deleteUserMemoryByKey(String userId, String key) {
        memoryMapper.deleteUserMemoryByKey(userId, key);
    }

    /** 测试页手动删除 */
    public int deleteUserMemoryById(String userId, long id) {
        return memoryMapper.deleteUserMemoryById(userId, id);
    }

    public Optional<String> findSessionUser(String sessionId) {
        return Optional.ofNullable(memoryMapper.findSessionUser(sessionId)).filter(u -> !u.isBlank());
    }
}

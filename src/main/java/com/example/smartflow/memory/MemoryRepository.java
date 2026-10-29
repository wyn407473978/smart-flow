package com.example.smartflow.memory;

import com.example.smartflow.flow.MemoryFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 滚动摘要（flow_memory_summary）和长期记忆（flow_user_memory）的存取。
 */
@Repository
public class MemoryRepository {

    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        List<SummaryRecord> rows = jdbc.query(
                "SELECT summary, up_to_message_id, summarized_count FROM flow_memory_summary WHERE session_id = ?",
                (rs, i) -> new SummaryRecord(rs.getString(1), rs.getLong(2), rs.getInt(3)),
                sessionId);
        return rows.isEmpty() ? SummaryRecord.EMPTY : rows.get(0);
    }

    public void saveSummary(String sessionId, SummaryRecord record) {
        jdbc.update("""
                INSERT INTO flow_memory_summary (session_id, summary, up_to_message_id, summarized_count, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (session_id) DO UPDATE
                SET summary = EXCLUDED.summary, up_to_message_id = EXCLUDED.up_to_message_id,
                    summarized_count = EXCLUDED.summarized_count, updated_at = now()
                """, sessionId, record.summary(), record.upToMessageId(), record.summarizedCount());
    }

    // ------------------------------------------------------------------ 长期记忆

    public List<MemoryFact> listUserMemories(String userId, int limit) {
        return jdbc.query("""
                        SELECT id, memory_key, memory_value, updated_at FROM flow_user_memory
                        WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?
                        """,
                (rs, i) -> new MemoryFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getObject(4))),
                userId, limit);
    }

    /** 同一用户同一个 key 只保留一条，新值覆盖旧值 */
    public void upsertUserMemory(String userId, String key, String value, String sessionId, String turnId) {
        jdbc.update("""
                INSERT INTO flow_user_memory (user_id, memory_key, memory_value, source_session_id, source_turn_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, memory_key) DO UPDATE
                SET memory_value = EXCLUDED.memory_value, source_session_id = EXCLUDED.source_session_id,
                    source_turn_id = EXCLUDED.source_turn_id, updated_at = now()
                """, userId, key, value, sessionId, turnId);
    }

    public void deleteUserMemoryByKey(String userId, String key) {
        jdbc.update("DELETE FROM flow_user_memory WHERE user_id = ? AND memory_key = ?", userId, key);
    }

    /** 测试页手动删除 */
    public int deleteUserMemoryById(String userId, long id) {
        return jdbc.update("DELETE FROM flow_user_memory WHERE user_id = ? AND id = ?", userId, id);
    }

    public Optional<String> findSessionUser(String sessionId) {
        List<String> rows = jdbc.query("SELECT user_id FROM flow_session WHERE session_id = ?",
                (rs, i) -> rs.getString(1), sessionId);
        return rows.stream().filter(u -> u != null && !u.isBlank()).findFirst();
    }
}

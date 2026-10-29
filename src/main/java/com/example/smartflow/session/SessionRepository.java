package com.example.smartflow.session;

import com.example.smartflow.flow.HistoryMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record SessionRecord(String sessionId, String userId, SessionState state, int turnCount) {
    }

    public Optional<SessionRecord> find(String sessionId) {
        List<SessionRecord> rows = jdbc.query(
                "SELECT session_id, user_id, state::text AS state, turn_count FROM flow_session WHERE session_id = ?",
                (rs, i) -> new SessionRecord(
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        readState(rs.getString("state")),
                        rs.getInt("turn_count")),
                sessionId);
        return rows.stream().findFirst();
    }

    /** 会话不存在就创建；已存在且还没绑定用户时，补上 userId */
    public void create(String sessionId, String userId) {
        jdbc.update("""
                INSERT INTO flow_session (session_id, user_id, state, turn_count)
                VALUES (?, ?, ?::jsonb, 0)
                ON CONFLICT (session_id) DO UPDATE SET user_id = COALESCE(flow_session.user_id, EXCLUDED.user_id)
                """, sessionId, userId, toJson(new SessionState()));
    }

    public void save(String sessionId, SessionState state, int turnCount) {
        jdbc.update("""
                UPDATE flow_session SET state = ?::jsonb, turn_count = ?, updated_at = now()
                WHERE session_id = ?
                """, toJson(state), turnCount, sessionId);
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return jdbc.queryForList("""
                SELECT session_id, user_id, turn_count, created_at, updated_at
                FROM flow_session ORDER BY updated_at DESC LIMIT ?
                """, limit);
    }

    // ------------------------------------------------------------------ messages

    public void insertMessage(String sessionId, String turnId, String role, String content, String nodeId) {
        jdbc.update("""
                INSERT INTO flow_message (session_id, turn_id, role, content, node_id)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, turnId, role, content, nodeId);
    }

    /**
     * id 大于 afterId 的消息里最新的 limit 条，按时间正序返回。
     * 滚动摘要把 afterId 之前的消息压缩掉了，所以每轮只需要读它之后的原文。
     */
    public List<HistoryMessage> messagesAfter(String sessionId, long afterId, int limit) {
        List<HistoryMessage> desc = jdbc.query("""
                        SELECT id, turn_id, role, content FROM flow_message
                        WHERE session_id = ? AND id > ? ORDER BY id DESC LIMIT ?
                        """,
                (rs, i) -> new HistoryMessage(rs.getString("role"), rs.getString("content"),
                        rs.getLong("id"), rs.getString("turn_id")),
                sessionId, afterId, limit);
        List<HistoryMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
    }

    /** 最近 n 条消息，按时间正序返回 */
    public List<HistoryMessage> recentMessages(String sessionId, int n) {
        List<HistoryMessage> desc = jdbc.query("""
                        SELECT role, content FROM flow_message
                        WHERE session_id = ? ORDER BY id DESC LIMIT ?
                        """,
                (rs, i) -> new HistoryMessage(rs.getString("role"), rs.getString("content")),
                sessionId, n);
        List<HistoryMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        return jdbc.queryForList("""
                SELECT id, turn_id, role, content, node_id, created_at
                FROM flow_message WHERE session_id = ? ORDER BY id
                """, sessionId);
    }

    // ------------------------------------------------------------------ json

    public String toJson(SessionState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化会话状态失败", e);
        }
    }

    private SessionState readState(String json) {
        if (json == null || json.isBlank()) {
            return new SessionState();
        }
        try {
            return mapper.readValue(json, SessionState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析会话状态失败: " + json, e);
        }
    }
}

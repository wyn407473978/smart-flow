package com.example.smartflow.session;

import com.example.smartflow.flow.HistoryMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话与消息的存取。SQL 在 {@link SessionMapper}（resources/mapper/SessionMapper.xml），
 * 这里负责会话状态的 JSON 序列化和结果整理，对外方法保持不变。
 */
@Repository
public class SessionRepository {

    private final SessionMapper sessionMapper;
    private final ObjectMapper mapper;

    public SessionRepository(SessionMapper sessionMapper, ObjectMapper mapper) {
        this.sessionMapper = sessionMapper;
        this.mapper = mapper;
    }

    public record SessionRecord(String sessionId, String userId, SessionState state, int turnCount) {
    }

    public Optional<SessionRecord> find(String sessionId) {
        return Optional.ofNullable(sessionMapper.findById(sessionId))
                .map(row -> new SessionRecord(row.sessionId(), row.userId(), readState(row.stateJson()), row.turnCount()));
    }

    /** 会话不存在就创建；已存在且还没绑定用户时，补上 userId */
    public void create(String sessionId, String userId) {
        sessionMapper.insertOrBindUser(sessionId, userId, toJson(new SessionState()));
    }

    public void save(String sessionId, SessionState state, int turnCount) {
        sessionMapper.updateState(sessionId, toJson(state), turnCount);
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return sessionMapper.listRecent(limit);
    }

    // ------------------------------------------------------------------ messages

    public void insertMessage(String sessionId, String turnId, String role, String content, String nodeId) {
        sessionMapper.insertMessage(sessionId, turnId, role, content, nodeId);
    }

    /**
     * id 大于 afterId 的消息里最新的 limit 条，按时间正序返回。
     * 滚动摘要把 afterId 之前的消息压缩掉了，所以每轮只需要读它之后的原文。
     */
    public List<HistoryMessage> messagesAfter(String sessionId, long afterId, int limit) {
        return ascending(sessionMapper.messagesAfterDesc(sessionId, afterId, limit));
    }

    /** 最近 n 条消息，按时间正序返回 */
    public List<HistoryMessage> recentMessages(String sessionId, int n) {
        return ascending(sessionMapper.recentMessagesDesc(sessionId, n));
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        return sessionMapper.listMessages(sessionId);
    }

    private static List<HistoryMessage> ascending(List<HistoryMessage> desc) {
        List<HistoryMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
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

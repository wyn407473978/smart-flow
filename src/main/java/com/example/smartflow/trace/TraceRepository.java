package com.example.smartflow.trace;

import com.example.smartflow.flow.ToolFact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 轮次 / 节点执行 / 模型调用 / 工具调用 的落库。
 * <p>
 * 最小闭环里是同步写入，量上来以后可以改成写内存队列 + 批量异步落库，接口不用变。
 */
@Repository
public class TraceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TraceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ turn

    public void insertTurn(String turnId, String sessionId, int seq, String userInput, Object stateBefore) {
        jdbc.update("""
                INSERT INTO flow_turn (turn_id, session_id, seq, user_input, state_before, status)
                VALUES (?, ?, ?, ?, ?::jsonb, 'RUNNING')
                """, turnId, sessionId, seq, userInput, json(stateBefore));
    }

    public void finishTurn(String turnId, String finalReply, String routeAction, String routeTarget,
                           List<String> nodePath, Object stateAfter, String status, String errorMsg, long latencyMs) {
        jdbc.update("""
                UPDATE flow_turn
                SET final_reply = ?, route_action = ?, route_target = ?, node_path = ?::jsonb,
                    state_after = ?::jsonb, status = ?, error_msg = ?, latency_ms = ?, finished_at = now()
                WHERE turn_id = ?
                """, finalReply, routeAction, routeTarget, json(nodePath), json(stateAfter),
                status, errorMsg, latencyMs, turnId);
    }

    // ------------------------------------------------------------------ node exec

    public void insertNodeExec(String execId, String turnId, String sessionId, int seq,
                               String nodeId, String nodeType, Object inputState) {
        jdbc.update("""
                INSERT INTO flow_node_exec (exec_id, turn_id, session_id, seq, node_id, node_type, input_state, status)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'RUNNING')
                """, execId, turnId, sessionId, seq, nodeId, nodeType, json(inputState));
    }

    public void finishNodeExec(String execId, Object outputUpdate, String status, String errorMsg, long latencyMs) {
        jdbc.update("""
                UPDATE flow_node_exec
                SET output_update = ?::jsonb, status = ?, error_msg = ?, latency_ms = ?, finished_at = now()
                WHERE exec_id = ?
                """, json(outputUpdate), status, errorMsg, latencyMs, execId);
    }

    // ------------------------------------------------------------------ llm call

    public void insertLlmCall(String callId, String turnId, String sessionId, String execId, String nodeId,
                              String purpose, String provider, String modelName, boolean streaming,
                              String requestBodyJson) {
        jdbc.update("""
                INSERT INTO flow_llm_call (call_id, turn_id, session_id, exec_id, node_id, purpose,
                                           provider, model_name, streaming, request_body, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'RUNNING')
                """, callId, turnId, sessionId, execId, nodeId, purpose, provider, modelName, streaming,
                requestBodyJson);
    }

    public void finishLlmCall(String callId, String responseBodyJson, String responseText,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens,
                              String finishReason, String status, String errorMsg, long latencyMs) {
        jdbc.update("""
                UPDATE flow_llm_call
                SET response_body = ?::jsonb, response_text = ?, input_tokens = ?, output_tokens = ?,
                    total_tokens = ?, finish_reason = ?, status = ?, error_msg = ?, latency_ms = ?,
                    finished_at = now()
                WHERE call_id = ?
                """, responseBodyJson, responseText, inputTokens, outputTokens, totalTokens,
                finishReason, status, errorMsg, latencyMs, callId);
    }

    // ------------------------------------------------------------------ tool call（B2 / C2 接入时使用）

    public void insertToolCall(String toolCallId, String turnId, String sessionId, String execId, String llmCallId,
                               String nodeId, String toolName, String argumentsJson) {
        jdbc.update("""
                INSERT INTO flow_tool_call (tool_call_id, turn_id, session_id, exec_id, llm_call_id,
                                            node_id, tool_name, arguments, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'RUNNING')
                """, toolCallId, turnId, sessionId, execId, llmCallId, nodeId, toolName, argumentsJson);
    }

    public void finishToolCall(String toolCallId, String result, String status, String errorMsg, long latencyMs) {
        jdbc.update("""
                UPDATE flow_tool_call
                SET result = ?, status = ?, error_msg = ?, latency_ms = ?, finished_at = now()
                WHERE tool_call_id = ?
                """, result, status, errorMsg, latencyMs, toolCallId);
    }

    /**
     * 指定轮次里成功的工具调用（不含内置的 ask_user），按时间正序。给记忆用：节点可以把最近查到的数据带进提示词。
     */
    public List<ToolFact> listToolFacts(String sessionId, Collection<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = List.copyOf(turnIds);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(ids);
        return jdbc.query("""
                        SELECT turn_id, tool_name, arguments::text AS arguments, result FROM flow_tool_call
                        WHERE session_id = ? AND status = 'SUCCESS' AND tool_name <> 'ask_user'
                          AND turn_id IN (%s)
                        ORDER BY started_at
                        """.formatted(placeholders),
                (rs, i) -> new ToolFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                args.toArray());
    }

    // ------------------------------------------------------------------ 查询（测试页面用）

    public List<Map<String, Object>> listTurns(String sessionId) {
        return jdbc.queryForList("""
                SELECT turn_id, seq, user_input, final_reply, route_action, route_target,
                       node_path::text AS node_path, status, error_msg, latency_ms, started_at
                FROM flow_turn WHERE session_id = ? ORDER BY seq
                """, sessionId);
    }

    public Map<String, Object> getTurn(String turnId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT turn_id, session_id, seq, user_input, final_reply, route_action, route_target,
                       node_path::text AS node_path, state_before::text AS state_before,
                       state_after::text AS state_after, status, error_msg, latency_ms, started_at
                FROM flow_turn WHERE turn_id = ?
                """, turnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listNodeExecs(String turnId) {
        return jdbc.queryForList("""
                SELECT exec_id, seq, node_id, node_type, input_state::text AS input_state,
                       output_update::text AS output_update, status, error_msg, latency_ms, started_at
                FROM flow_node_exec WHERE turn_id = ? ORDER BY seq
                """, turnId);
    }

    public List<Map<String, Object>> listLlmCalls(String turnId) {
        return jdbc.queryForList("""
                SELECT call_id, exec_id, node_id, purpose, provider, model_name, streaming,
                       request_body::text AS request_body, response_body::text AS response_body,
                       response_text, input_tokens, output_tokens, total_tokens, finish_reason,
                       status, error_msg, latency_ms, started_at
                FROM flow_llm_call WHERE turn_id = ? ORDER BY started_at
                """, turnId);
    }

    public List<Map<String, Object>> listToolCalls(String turnId) {
        return jdbc.queryForList("""
                SELECT tool_call_id, exec_id, llm_call_id, node_id, tool_name, arguments::text AS arguments,
                       result, status, error_msg, latency_ms, started_at
                FROM flow_tool_call WHERE turn_id = ? ORDER BY started_at
                """, turnId);
    }

    // ------------------------------------------------------------------ util

    public String json(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 日志不能影响主流程
            return "{\"_serializeError\":\"" + e.getOriginalMessage().replace("\"", "'") + "\"}";
        }
    }
}

package com.example.smartflow.trace;

import com.example.smartflow.flow.ToolFact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 轮次 / 节点执行 / 模型调用 / 工具调用 的落库。SQL 在 {@link TraceMapper}（同路径的 TraceMapper.xml），
 * 这里负责把对象序列化成 JSON，对外方法保持不变。
 * <p>
 * 最小闭环里是同步写入，量上来以后可以改成写内存队列 + 批量异步落库，接口不用变。
 */
@Repository
public class TraceRepository {

    private final TraceMapper traceMapper;
    private final ObjectMapper mapper;

    public TraceRepository(TraceMapper traceMapper, ObjectMapper mapper) {
        this.traceMapper = traceMapper;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ turn

    public void insertTurn(String turnId, String sessionId, int seq, String userInput, Object stateBefore) {
        traceMapper.insertTurn(turnId, sessionId, seq, userInput, json(stateBefore));
    }

    public void finishTurn(String turnId, String finalReply, String routeAction, String routeTarget,
                           List<String> nodePath, Object stateAfter, String status, String errorMsg, long latencyMs) {
        traceMapper.finishTurn(turnId, finalReply, routeAction, routeTarget, json(nodePath), json(stateAfter),
                status, errorMsg, latencyMs);
    }

    // ------------------------------------------------------------------ node exec

    public void insertNodeExec(String execId, String turnId, String sessionId, int seq,
                               String nodeId, String nodeType, Object inputState) {
        traceMapper.insertNodeExec(execId, turnId, sessionId, seq, nodeId, nodeType, json(inputState));
    }

    public void finishNodeExec(String execId, Object outputUpdate, String status, String errorMsg, long latencyMs) {
        traceMapper.finishNodeExec(execId, json(outputUpdate), status, errorMsg, latencyMs);
    }

    // ------------------------------------------------------------------ llm call

    public void insertLlmCall(String callId, String turnId, String sessionId, String execId, String nodeId,
                              String purpose, String provider, String modelName, boolean streaming,
                              String requestBodyJson) {
        traceMapper.insertLlmCall(callId, turnId, sessionId, execId, nodeId, purpose, provider, modelName,
                streaming, requestBodyJson);
    }

    public void finishLlmCall(String callId, String responseBodyJson, String responseText,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens,
                              String finishReason, String status, String errorMsg, long latencyMs) {
        traceMapper.finishLlmCall(callId, responseBodyJson, responseText, inputTokens, outputTokens, totalTokens,
                finishReason, status, errorMsg, latencyMs);
    }

    // ------------------------------------------------------------------ tool call（B2 / C2 接入时使用）

    public void insertToolCall(String toolCallId, String turnId, String sessionId, String execId, String llmCallId,
                               String nodeId, String toolName, String argumentsJson) {
        traceMapper.insertToolCall(toolCallId, turnId, sessionId, execId, llmCallId, nodeId, toolName, argumentsJson);
    }

    public void finishToolCall(String toolCallId, String result, String status, String errorMsg, long latencyMs) {
        traceMapper.finishToolCall(toolCallId, result, status, errorMsg, latencyMs);
    }

    /**
     * 指定轮次里成功的工具调用（不含内置的 ask_user），按时间正序。给记忆用：节点可以把最近查到的数据带进提示词。
     */
    public List<ToolFact> listToolFacts(String sessionId, Collection<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return List.of();
        }
        return traceMapper.listToolFacts(sessionId, turnIds);
    }

    // ------------------------------------------------------------------ 查询（测试页面用）

    public List<Map<String, Object>> listTurns(String sessionId) {
        return traceMapper.listTurns(sessionId);
    }

    public Map<String, Object> getTurn(String turnId) {
        return traceMapper.getTurn(turnId);
    }

    public List<Map<String, Object>> listNodeExecs(String turnId) {
        return traceMapper.listNodeExecs(turnId);
    }

    public List<Map<String, Object>> listLlmCalls(String turnId) {
        return traceMapper.listLlmCalls(turnId);
    }

    public List<Map<String, Object>> listToolCalls(String turnId) {
        return traceMapper.listToolCalls(turnId);
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

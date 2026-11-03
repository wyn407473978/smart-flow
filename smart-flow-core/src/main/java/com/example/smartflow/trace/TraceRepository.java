package com.example.smartflow.trace;

import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.trace.langfuse.LangfuseTracing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 轮次 / 节点执行 / 模型调用 / 工具调用 的统一存储入口，负责 JSON 序列化。
 * SQL 按表拆分到 {@link TurnMapper}、{@link NodeExecMapper}、{@link LlmCallMapper}、
 * {@link ToolCallMapper} 及同路径 XML。
 * <p>
 * 最小闭环里是同步写入，量上来以后可以改成写内存队列 + 批量异步落库，接口不用变。
 */
@Repository
public class TraceRepository {

    private final TurnMapper turnMapper;
    private final NodeExecMapper nodeExecMapper;
    private final LlmCallMapper llmCallMapper;
    private final ToolCallMapper toolCallMapper;
    private final ObjectMapper mapper;
    private final LangfuseTracing langfuse;

    public TraceRepository(TurnMapper turnMapper, NodeExecMapper nodeExecMapper,
                           LlmCallMapper llmCallMapper, ToolCallMapper toolCallMapper, ObjectMapper mapper) {
        this(turnMapper, nodeExecMapper, llmCallMapper, toolCallMapper, mapper, null);
    }

    @Autowired
    public TraceRepository(TurnMapper turnMapper, NodeExecMapper nodeExecMapper,
                           LlmCallMapper llmCallMapper, ToolCallMapper toolCallMapper, ObjectMapper mapper,
                           @Nullable LangfuseTracing langfuse) {
        this.turnMapper = turnMapper;
        this.nodeExecMapper = nodeExecMapper;
        this.llmCallMapper = llmCallMapper;
        this.toolCallMapper = toolCallMapper;
        this.mapper = mapper;
        this.langfuse = langfuse;
    }

    // ------------------------------------------------------------------ turn

    public void insertTurn(String turnId, String sessionId, int seq, String userInput, Object stateBefore) {
        turnMapper.insertTurn(turnId, sessionId, seq, userInput, json(stateBefore));
        if (langfuse != null) langfuse.startTurn(turnId, sessionId, null, userInput);
    }

    public void insertTurn(String turnId, String sessionId, String userId, int seq, String userInput, Object stateBefore) {
        // 保留已有子类（例如内存测试存储）覆盖原方法的行为。
        insertTurn(turnId, sessionId, seq, userInput, stateBefore);
        if (langfuse != null) langfuse.identifyUser(turnId, userId);
    }

    public void finishTurn(String turnId, String finalReply, String routeAction, String routeTarget,
                           List<String> nodePath, Object stateAfter, String status, String errorMsg, long latencyMs) {
        try {
            turnMapper.finishTurn(turnId, finalReply, routeAction, routeTarget, json(nodePath), json(stateAfter),
                    status, errorMsg, latencyMs);
        } finally {
            if (langfuse != null) langfuse.endTurn(turnId, finalReply, status, errorMsg);
        }
    }

    // ------------------------------------------------------------------ node exec

    public void insertNodeExec(String execId, String turnId, String sessionId, int seq,
                               String nodeId, String nodeType, Object inputState) {
        nodeExecMapper.insertNodeExec(execId, turnId, sessionId, seq, nodeId, nodeType, json(inputState));
        if (langfuse != null) langfuse.startNode(execId, turnId, nodeId, nodeType, inputState);
    }

    public void finishNodeExec(String execId, Object outputUpdate, String status, String errorMsg, long latencyMs) {
        try {
            nodeExecMapper.finishNodeExec(execId, json(outputUpdate), status, errorMsg, latencyMs);
        } finally {
            if (langfuse != null) langfuse.endNode(execId, outputUpdate, status, errorMsg);
        }
    }

    // ------------------------------------------------------------------ llm call

    public void insertLlmCall(String callId, String turnId, String sessionId, String execId, String nodeId,
                              String purpose, String provider, String modelName, boolean streaming,
                              String requestBodyJson) {
        llmCallMapper.insertLlmCall(callId, turnId, sessionId, execId, nodeId, purpose, provider, modelName,
                streaming, requestBodyJson);
        if (langfuse != null) langfuse.startGeneration(callId, turnId, sessionId, execId, nodeId,
                purpose, provider, modelName, streaming, requestBodyJson);
    }

    public void finishLlmCall(String callId, String responseBodyJson, String responseText,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens,
                              String finishReason, String status, String errorMsg, long latencyMs) {
        try {
            llmCallMapper.finishLlmCall(callId, responseBodyJson, responseText, inputTokens, outputTokens, totalTokens,
                    finishReason, status, errorMsg, latencyMs);
        } finally {
            if (langfuse != null) langfuse.endGeneration(callId, responseBodyJson, responseText,
                    inputTokens, outputTokens, totalTokens, finishReason, status, errorMsg);
        }
    }

    // ------------------------------------------------------------------ tool call

    public void insertToolCall(String toolCallId, String turnId, String sessionId, String execId, String llmCallId,
                               String nodeId, String toolName, String argumentsJson) {
        toolCallMapper.insertToolCall(toolCallId, turnId, sessionId, execId, llmCallId, nodeId, toolName, argumentsJson);
        if (langfuse != null) langfuse.startTool(toolCallId, turnId, execId, llmCallId, toolName, argumentsJson);
    }

    public void finishToolCall(String toolCallId, String result, String status, String errorMsg, long latencyMs) {
        try {
            toolCallMapper.finishToolCall(toolCallId, result, status, errorMsg, latencyMs);
        } finally {
            if (langfuse != null) langfuse.endTool(toolCallId, result, status, errorMsg);
        }
    }

    /**
     * 指定轮次里成功的工具调用（不含内置的 ask_user），按时间正序。给记忆用：节点可以把最近查到的数据带进提示词。
     */
    public List<ToolFact> listToolFacts(String sessionId, Collection<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return List.of();
        }
        return toolCallMapper.listToolFacts(sessionId, turnIds);
    }

    // ------------------------------------------------------------------ 查询（测试页面用）

    public List<Map<String, Object>> listTurns(String sessionId) {
        return turnMapper.listTurns(sessionId);
    }

    public Map<String, Object> getTurn(String turnId) {
        return turnMapper.getTurn(turnId);
    }

    public List<Map<String, Object>> listNodeExecs(String turnId) {
        return nodeExecMapper.listNodeExecs(turnId);
    }

    public List<Map<String, Object>> listLlmCalls(String turnId) {
        return llmCallMapper.listLlmCalls(turnId);
    }

    public List<Map<String, Object>> listToolCalls(String turnId) {
        return toolCallMapper.listToolCalls(turnId);
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

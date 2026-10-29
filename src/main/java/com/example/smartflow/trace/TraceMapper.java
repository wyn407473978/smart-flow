package com.example.smartflow.trace;

import com.example.smartflow.flow.ToolFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 轮次 / 节点执行 / 模型调用 / 工具调用 的 SQL，写在 resources/mapper/TraceMapper.xml。
 * JSON 字段一律以字符串传入、在 SQL 里转 jsonb；序列化由 {@link TraceRepository} 负责。
 */
@Mapper
public interface TraceMapper {

    // ------------------------------------------------------------------ turn

    int insertTurn(@Param("turnId") String turnId,
                   @Param("sessionId") String sessionId,
                   @Param("seq") int seq,
                   @Param("userInput") String userInput,
                   @Param("stateBeforeJson") String stateBeforeJson);

    int finishTurn(@Param("turnId") String turnId,
                   @Param("finalReply") String finalReply,
                   @Param("routeAction") String routeAction,
                   @Param("routeTarget") String routeTarget,
                   @Param("nodePathJson") String nodePathJson,
                   @Param("stateAfterJson") String stateAfterJson,
                   @Param("status") String status,
                   @Param("errorMsg") String errorMsg,
                   @Param("latencyMs") long latencyMs);

    // ------------------------------------------------------------------ node exec

    int insertNodeExec(@Param("execId") String execId,
                       @Param("turnId") String turnId,
                       @Param("sessionId") String sessionId,
                       @Param("seq") int seq,
                       @Param("nodeId") String nodeId,
                       @Param("nodeType") String nodeType,
                       @Param("inputStateJson") String inputStateJson);

    int finishNodeExec(@Param("execId") String execId,
                       @Param("outputUpdateJson") String outputUpdateJson,
                       @Param("status") String status,
                       @Param("errorMsg") String errorMsg,
                       @Param("latencyMs") long latencyMs);

    // ------------------------------------------------------------------ llm call

    int insertLlmCall(@Param("callId") String callId,
                      @Param("turnId") String turnId,
                      @Param("sessionId") String sessionId,
                      @Param("execId") String execId,
                      @Param("nodeId") String nodeId,
                      @Param("purpose") String purpose,
                      @Param("provider") String provider,
                      @Param("modelName") String modelName,
                      @Param("streaming") boolean streaming,
                      @Param("requestBodyJson") String requestBodyJson);

    int finishLlmCall(@Param("callId") String callId,
                      @Param("responseBodyJson") String responseBodyJson,
                      @Param("responseText") String responseText,
                      @Param("inputTokens") Integer inputTokens,
                      @Param("outputTokens") Integer outputTokens,
                      @Param("totalTokens") Integer totalTokens,
                      @Param("finishReason") String finishReason,
                      @Param("status") String status,
                      @Param("errorMsg") String errorMsg,
                      @Param("latencyMs") long latencyMs);

    // ------------------------------------------------------------------ tool call

    int insertToolCall(@Param("toolCallId") String toolCallId,
                       @Param("turnId") String turnId,
                       @Param("sessionId") String sessionId,
                       @Param("execId") String execId,
                       @Param("llmCallId") String llmCallId,
                       @Param("nodeId") String nodeId,
                       @Param("toolName") String toolName,
                       @Param("argumentsJson") String argumentsJson);

    int finishToolCall(@Param("toolCallId") String toolCallId,
                       @Param("result") String result,
                       @Param("status") String status,
                       @Param("errorMsg") String errorMsg,
                       @Param("latencyMs") long latencyMs);

    /** turnIds 不能为空，调用方先判断 */
    List<ToolFact> listToolFacts(@Param("sessionId") String sessionId,
                                 @Param("turnIds") Collection<String> turnIds);

    // ------------------------------------------------------------------ 查询（测试页面用）

    List<Map<String, Object>> listTurns(@Param("sessionId") String sessionId);

    /** 不存在时返回 null */
    Map<String, Object> getTurn(@Param("turnId") String turnId);

    List<Map<String, Object>> listNodeExecs(@Param("turnId") String turnId);

    List<Map<String, Object>> listLlmCalls(@Param("turnId") String turnId);

    List<Map<String, Object>> listToolCalls(@Param("turnId") String turnId);
}

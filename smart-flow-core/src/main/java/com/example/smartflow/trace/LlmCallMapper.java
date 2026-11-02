package com.example.smartflow.trace;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 模型调用记录，只操作 {@code flow_llm_call} 表。SQL 位于同路径的 LlmCallMapper.xml。
 * JSON 字段以字符串传入，由 SQL 转成 jsonb；序列化由 {@link TraceRepository} 负责。
 * 由 FlowCoreAutoConfiguration 注册，不使用 @Mapper 扫描。
 */
public interface LlmCallMapper {

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

    List<Map<String, Object>> listLlmCalls(@Param("turnId") String turnId);
}

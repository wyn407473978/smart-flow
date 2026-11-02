package com.example.smartflow.trace;

import com.example.smartflow.flow.ToolFact;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工具调用记录，只操作 {@code flow_tool_call} 表。SQL 位于同路径的 ToolCallMapper.xml。
 * JSON 字段以字符串传入，由 SQL 转成 jsonb；序列化由 {@link TraceRepository} 负责。
 * 由 FlowCoreAutoConfiguration 注册，不使用 @Mapper 扫描。
 */
public interface ToolCallMapper {

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

    /** 指定轮次里成功的工具调用（不含 ask_user）；turnIds 不能为空，由调用方先判断。 */
    List<ToolFact> listToolFacts(@Param("sessionId") String sessionId,
                                 @Param("turnIds") Collection<String> turnIds);

    List<Map<String, Object>> listToolCalls(@Param("turnId") String turnId);
}

package com.example.smartflow.trace;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 轮次记录，只操作 {@code flow_turn} 表。SQL 位于同路径的 TurnMapper.xml。
 * JSON 字段以字符串传入，由 SQL 转成 jsonb；序列化由 {@link TraceRepository} 负责。
 * 由 FlowCoreAutoConfiguration 注册，不使用 @Mapper 扫描。
 */
public interface TurnMapper {

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

    List<Map<String, Object>> listTurns(@Param("sessionId") String sessionId);

    /** 不存在时返回 null。 */
    Map<String, Object> getTurn(@Param("turnId") String turnId);
}

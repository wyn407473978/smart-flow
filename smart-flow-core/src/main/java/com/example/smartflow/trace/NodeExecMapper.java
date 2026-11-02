package com.example.smartflow.trace;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 节点执行记录，只操作 {@code flow_node_exec} 表。SQL 位于同路径的 NodeExecMapper.xml。
 * JSON 字段以字符串传入，由 SQL 转成 jsonb；序列化由 {@link TraceRepository} 负责。
 * 由 FlowCoreAutoConfiguration 注册，不使用 @Mapper 扫描。
 */
public interface NodeExecMapper {

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

    List<Map<String, Object>> listNodeExecs(@Param("turnId") String turnId);
}

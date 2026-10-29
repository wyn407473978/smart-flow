package com.example.smartflow.memory;

import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.memory.MemoryRepository.SummaryRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_memory_summary / flow_user_memory 的 SQL，写在同路径的 MemoryMapper.xml（resources/com/example/smartflow/memory/MemoryMapper.xml）。
 * 由 FlowCoreAutoConfiguration 注册（没有 @Mapper 注解，避免被业务工程的 Mapper 扫描重复注册）。
 * 业务代码统一走 {@link MemoryRepository}。
 */
public interface MemoryMapper {

    // ------------------------------------------------------------------ 滚动摘要

    /** 没有摘要时返回 null */
    SummaryRecord findSummary(@Param("sessionId") String sessionId);

    int upsertSummary(@Param("sessionId") String sessionId, @Param("r") SummaryRecord record);

    // ------------------------------------------------------------------ 长期记忆

    List<MemoryFact> listUserMemories(@Param("userId") String userId, @Param("limit") int limit);

    int upsertUserMemory(@Param("userId") String userId,
                         @Param("key") String key,
                         @Param("value") String value,
                         @Param("sessionId") String sessionId,
                         @Param("turnId") String turnId);

    int deleteUserMemoryByKey(@Param("userId") String userId, @Param("key") String key);

    int deleteUserMemoryById(@Param("userId") String userId, @Param("id") long id);

    /** 会话绑定的 userId，可能为 null */
    String findSessionUser(@Param("sessionId") String sessionId);
}

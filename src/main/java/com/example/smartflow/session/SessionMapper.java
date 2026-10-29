package com.example.smartflow.session;

import com.example.smartflow.flow.HistoryMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * flow_session / flow_message 的 SQL，写在 resources/mapper/SessionMapper.xml。
 * 业务代码不直接用它，统一走 {@link SessionRepository}（JSON 序列化、排序翻转等逻辑在那里）。
 */
@Mapper
public interface SessionMapper {

    /** state 以 JSON 文本返回，由 Repository 反序列化 */
    record SessionRow(String sessionId, String userId, String stateJson, int turnCount) {
    }

    SessionRow findById(@Param("sessionId") String sessionId);

    int insertOrBindUser(@Param("sessionId") String sessionId,
                         @Param("userId") String userId,
                         @Param("stateJson") String stateJson);

    int updateState(@Param("sessionId") String sessionId,
                    @Param("stateJson") String stateJson,
                    @Param("turnCount") int turnCount);

    List<Map<String, Object>> listRecent(@Param("limit") int limit);

    // ------------------------------------------------------------------ messages

    int insertMessage(@Param("sessionId") String sessionId,
                      @Param("turnId") String turnId,
                      @Param("role") String role,
                      @Param("content") String content,
                      @Param("nodeId") String nodeId);

    /** id 大于 afterId 的消息里最新的 limit 条，按 id 倒序 */
    List<HistoryMessage> messagesAfterDesc(@Param("sessionId") String sessionId,
                                           @Param("afterId") long afterId,
                                           @Param("limit") int limit);

    /** 最近 n 条消息（只有 role、content），按 id 倒序 */
    List<HistoryMessage> recentMessagesDesc(@Param("sessionId") String sessionId,
                                            @Param("limit") int limit);

    List<Map<String, Object>> listMessages(@Param("sessionId") String sessionId);
}

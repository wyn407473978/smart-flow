package com.example.smartflow.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推给前端的事件（SSE 的 event name + data）。
 * <ul>
 *   <li>turn：本轮开始，带 turnId</li>
 *   <li>node_start / node_end：节点开始 / 结束</li>
 *   <li>route：路由结果</li>
 *   <li>token：回复片段（流式）</li>
 *   <li>done：本轮结束，带完整回复和最新会话状态</li>
 *   <li>error：出错</li>
 * </ul>
 */
public record TurnEvent(String type, Map<String, Object> data) {

    public static TurnEvent of(String type, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new TurnEvent(type, data);
    }

    public static TurnEvent token(String text) {
        return of("token", "text", text);
    }
}

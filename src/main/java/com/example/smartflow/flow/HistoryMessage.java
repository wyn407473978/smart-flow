package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 短期记忆里的一条消息。状态里不直接放 LangChain4j 的 ChatMessage，
 * 避免 LangGraph4j 克隆状态时的序列化问题，需要时由节点转换。
 *
 * @param role user / assistant
 */
public record HistoryMessage(String role, String content) implements Serializable {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public boolean isUser() {
        return USER.equals(role);
    }
}

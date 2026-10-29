package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 短期记忆里的一条消息。状态里不直接放 LangChain4j 的 ChatMessage，
 * 避免 LangGraph4j 克隆状态时的序列化问题，需要时由节点转换。
 *
 * @param role    user / assistant
 * @param content 消息原文
 * @param id      flow_message.id，摘要用它记录"压缩到了哪一条"；内存中新建的消息可以为 null
 * @param turnId  所属轮次，用来关联这一轮的工具调用
 */
public record HistoryMessage(String role, String content, Long id, String turnId) implements Serializable {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public HistoryMessage(String role, String content) {
        this(role, content, null, null);
    }

    public boolean isUser() {
        return USER.equals(role);
    }
}

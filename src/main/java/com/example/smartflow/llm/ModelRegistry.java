package com.example.smartflow.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 节点只依赖这个注册表拿模型。做 Evals 时可以整体替换成 stub 模型。
 *
 * @param chat      主模型（槽位抽取、需要非流式结果的场景）
 * @param router    路由模型（每轮都调用，轻量、温度 0）
 * @param streaming 流式模型（直接回复用户的场景）
 * @param memory    后台记忆任务模型（滚动摘要、长期记忆抽取），不在用户等待的链路上
 */
public record ModelRegistry(ChatModel chat, ChatModel router, StreamingChatModel streaming, ChatModel memory) {

    /** 不单独指定记忆模型时，复用路由模型 */
    public ModelRegistry(ChatModel chat, ChatModel router, StreamingChatModel streaming) {
        this(chat, router, streaming, router);
    }
}

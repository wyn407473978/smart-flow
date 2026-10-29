package com.example.smartflow.memory;

import com.example.smartflow.flow.HistoryMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 滚动摘要：把"已有摘要 + 一段较早的对话"合并成新的摘要。
 * 提示词按通用对话写，不绑定具体业务；某个工作流需要特别保留某类信息时，可以继承本类覆盖 {@link #systemPrompt(int)}。
 */
@Component
public class ConversationSummarizer {

    private static final String PROMPT = """
            你是对话摘要模块。请把【已有摘要】和【新增对话】合并成一份新的摘要，供助手在后续对话中回忆前情。

            要求：
            1. 保留：用户提出过的诉求、已经确定的事实和数据（人名、地点、时间、数字、选项）、做过的决定、还没完成的事项。
            2. 去掉：寒暄、客套话、重复的内容、助手的解释性话术。
            3. 用第三人称客观陈述（"用户……""助手……"），不要编造对话里没有的内容。
            4. 不超过 %d 字。
            5. 只输出摘要正文，不要标题、不要解释。
            """;

    public String systemPrompt(int maxChars) {
        return PROMPT.formatted(maxChars);
    }

    /**
     * @return 新的摘要；模型返回空时返回空字符串（调用方据此不推进摘要位置）
     */
    public String summarize(ChatModel model, String oldSummary, List<HistoryMessage> messages, int maxChars,
                            ChatRequestOptions options) {
        StringBuilder dialog = new StringBuilder();
        for (HistoryMessage m : messages) {
            dialog.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        String user = """
                【已有摘要】
                %s

                【新增对话】
                %s""".formatted(oldSummary == null || oldSummary.isBlank() ? "（无）" : oldSummary, dialog);

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt(maxChars)), UserMessage.from(user))
                .temperature(0.0)
                .build();
        String text = model.chat(request, options).aiMessage().text();
        return text == null ? "" : text.strip();
    }
}

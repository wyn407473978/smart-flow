package com.example.smartflow.memory;

import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.llm.LlmJson;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆抽取：从一轮对话里找出关于用户本人的稳定信息，产出对已有记忆的增删改。
 * 某个工作流要记的东西不一样时（例如医疗场景要记过敏史），继承本类覆盖 {@link #systemPrompt()}。
 */
@Component
public class LongTermMemoryExtractor {

    private static final String PROMPT = """
            你是长期记忆模块。请从【本轮对话】中找出关于用户本人的、长期稳定、以后的对话还用得上的信息，用来更新【已有记忆】。

            可以记：
            - 用户希望的称呼、用户自己说的姓名
            - 常住城市、所在社区或区域
            - 家庭情况（例如家里有老人、孩子）
            - 用户明确表达的长期偏好（例如"以后都用简短的回答"）

            不要记：
            - 一次性的问题和查询内容（例如"查一下今天的天气"）
            - 助手说的话、工具查询返回的数据
            - 你推测出来、用户没有明确说的内容
            - 身份证号、银行卡号、手机号、密码、健康状况等敏感信息

            规则：
            1. 只有用户本人明确说出的信息才记。
            2. 用户纠正或否定了已有记忆时，用新值覆盖（upserts）或删除（deletes）。
            3. key 用简短的中文名词（例如：称呼、常住城市）；同一类信息沿用已有记忆里的 key，不要另起新 key。
            4. 本轮没有需要记的内容是最常见的情况，这时输出空数组。

            只输出 JSON，不要输出其他内容，格式：
            {"upserts": [{"key": "常住城市", "value": "杭州"}], "deletes": ["要删除的 key"]}
            """;

    public static final int MAX_KEY_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 200;

    /** 模型输出 */
    public record Extraction(List<Item> upserts, List<String> deletes) {
        public static final Extraction EMPTY = new Extraction(List.of(), List.of());

        public Extraction {
            upserts = upserts == null ? List.of() : upserts;
            deletes = deletes == null ? List.of() : deletes;
        }

        public boolean isEmpty() {
            return upserts.isEmpty() && deletes.isEmpty();
        }
    }

    public record Item(String key, String value) {
    }

    public String systemPrompt() {
        return PROMPT;
    }

    public Extraction extract(ChatModel model, List<MemoryFact> existing, String userInput, String reply,
                              ChatRequestOptions options) {
        StringBuilder known = new StringBuilder();
        for (MemoryFact f : existing) {
            known.append("- ").append(f.key()).append("：").append(f.value()).append('\n');
        }
        String user = """
                【已有记忆】
                %s
                【本轮对话】
                用户：%s
                助手：%s""".formatted(known.isEmpty() ? "（无）\n" : known, userInput, reply);

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt()), UserMessage.from(user))
                .responseFormat(ResponseFormat.JSON)
                .temperature(0.0)
                .build();
        String text = model.chat(request, options).aiMessage().text();
        try {
            return sanitize(LlmJson.MAPPER.readValue(LlmJson.stripCodeFence(text), Extraction.class));
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆抽取结果不是合法 JSON: " + text, e);
        }
    }

    /** 去掉空值、过长的值，防止模型输出异常把记忆表写坏 */
    static Extraction sanitize(Extraction ex) {
        List<Item> upserts = new ArrayList<>();
        for (Item item : ex.upserts()) {
            if (item == null || item.key() == null || item.value() == null) {
                continue;
            }
            String k = item.key().strip();
            String v = item.value().strip();
            if (k.isEmpty() || v.isEmpty() || k.length() > MAX_KEY_LENGTH || v.length() > MAX_VALUE_LENGTH) {
                continue;
            }
            upserts.add(new Item(k, v));
        }
        List<String> deletes = ex.deletes().stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::strip)
                .toList();
        return new Extraction(upserts, deletes);
    }
}

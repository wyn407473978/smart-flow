package com.example.smartflow.llm;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.trace.TraceKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** 节点的模型调用能力：trace 关联、JSON 解析重试和流式输出。每次调用的状态都保存在局部变量中。 */
public final class NodeModelClient {

    private static final Logger log = LoggerFactory.getLogger(NodeModelClient.class);

    private final ModelRegistry models;
    private final FlowProperties props;
    private final ObjectMapper jsonMapper;

    public NodeModelClient(ModelRegistry models, FlowProperties props, ObjectMapper jsonMapper) {
        this.models = models;
        this.props = props;
        this.jsonMapper = jsonMapper;
    }

    public ChatRequestOptions traceOptions(NodeScope scope, String purpose, boolean streaming, String callId) {
        ChatRequestOptions.Builder b = ChatRequestOptions.builder()
                .addListenerAttribute(TraceKeys.TURN_ID, scope.turn().turnId())
                .addListenerAttribute(TraceKeys.SESSION_ID, scope.turn().sessionId())
                .addListenerAttribute(TraceKeys.EXEC_ID, scope.execId())
                .addListenerAttribute(TraceKeys.NODE_ID, scope.nodeId())
                .addListenerAttribute(TraceKeys.PURPOSE, purpose)
                .addListenerAttribute(TraceKeys.STREAMING, streaming);
        if (callId != null) {
            b.addListenerAttribute(TraceKeys.CALL_ID, callId);
        }
        return b.build();
    }

    public ChatResponse chat(ChatModel model, ChatRequest request, ChatRequestOptions options) {
        return model.chat(request, options);
    }

    /** 调用入口由节点传入，保证子类对普通 chat 的定制同样作用于 JSON 重试。 */
    public <T> T chatJson(NodeScope scope, String purpose, List<ChatMessage> messages, Class<T> type,
                          BiFunction<String, ChatRequest, ChatResponse> chat) {
        List<ChatMessage> msgs = new ArrayList<>(messages);
        Exception last = null;
        String lastText = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(msgs)
                    .responseFormat(ResponseFormat.JSON)
                    .temperature(0.0)
                    .build();
            ChatResponse response = chat.apply(attempt == 1 ? purpose : purpose + "#retry", request);
            lastText = response.aiMessage().text();
            try {
                return jsonMapper.readValue(LlmJson.stripCodeFence(lastText), type);
            } catch (Exception e) {
                last = e;
                log.warn("模型 JSON 解析失败 node={} purpose={} attempt={} text={}", scope.nodeId(), purpose, attempt, lastText);
                msgs.add(response.aiMessage());
                msgs.add(UserMessage.from("你上面的输出不是合法的 JSON。请只输出符合要求的 JSON 对象，不要包含任何其他文字。"));
            }
        }
        throw new LlmJsonException(purpose, lastText, last);
    }

    public String streamReply(NodeScope scope, List<ChatMessage> messages, ChatRequestOptions options) throws Exception {
        StringBuilder text = new StringBuilder();
        streamChat(scope, ChatRequest.builder().messages(messages).build(), options, text);
        return text.toString();
    }

    /** 一次流式调用；同一工具循环中的多次调用共用 text，以保持前端 token 与最终文本一致。 */
    public ChatResponse streamChat(NodeScope scope, ChatRequest request, ChatRequestOptions options,
                                    StringBuilder text) throws Exception {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        boolean[] first = {true};
        models.streaming().chat(request, options, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                if (first[0]) {
                    first[0] = false;
                    beginRound(scope, text);
                }
                text.append(partial);
                scope.turn().emitToken(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        int timeout = props.model().timeoutSeconds() == null ? 60 : props.model().timeoutSeconds();
        return done.get(timeout + 10L, TimeUnit.SECONDS);
    }

    private static void beginRound(NodeScope scope, StringBuilder text) {
        if (text.isEmpty()) {
            scope.turn().beginReply();
        } else if (text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
            scope.turn().emitToken("\n");
        }
    }

    /** 工具循环的提问或兜底文本也使用相同的分隔和 token 推送规则。 */
    public static void appendRoundText(NodeScope scope, StringBuilder text, String s) {
        beginRound(scope, text);
        text.append(s);
        scope.turn().emitToken(s);
    }

    public String emitText(NodeScope scope, String text) {
        scope.turn().beginReply();
        scope.turn().emitToken(text);
        return text;
    }
}

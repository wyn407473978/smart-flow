package com.example.smartflow.llm;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.trace.TraceKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** {@link #streamGuarded} 的结果 */
    public enum GuardedStream {
        /** 正常输出完毕 */
        COMPLETED,
        /** 第一个字之前就超时、出错或模型没有输出：什么都没有推给前端，text 没变 */
        NOT_STARTED,
        /** 输出了一部分之后出错或超时：已输出的部分留在 text 里，之后到达的内容会被丢弃 */
        INTERRUPTED
    }

    /**
     * 有兜底的流式调用：第一个字在 firstTokenTimeout 内没到就放弃，不往前端推任何内容，由调用方换成固定文案。
     * 放弃之后模型才陆续返回的内容会被丢弃，不会混进已经发出的回复。
     * <p>
     * text 里已有内容时（例如代码先输出了"当前已记录：..."），模型输出另起一行接在后面。
     * 本方法不抛异常：模型的错误只记日志，体现在返回值里。
     */
    public GuardedStream streamGuarded(NodeScope scope, StreamingChatModel model, ChatRequest request,
                                       ChatRequestOptions options, StringBuilder text, Duration firstTokenTimeout) {
        Object lock = new Object();
        // started：已经推出过内容；closed：调用方不再接收，之后到达的内容一律丢弃
        boolean[] started = {false};
        boolean[] closed = {false};
        CompletableFuture<Void> firstToken = new CompletableFuture<>();
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        try {
            model.chat(request, options, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    if (partial == null || partial.isEmpty()) {
                        return;
                    }
                    synchronized (lock) {
                        if (closed[0]) {
                            return;
                        }
                        if (!started[0]) {
                            started[0] = true;
                            beginRound(scope, text);
                        }
                        text.append(partial);
                        scope.turn().emitToken(partial);
                    }
                    firstToken.complete(null);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    done.complete(response);
                    firstToken.complete(null);
                }

                @Override
                public void onError(Throwable error) {
                    done.completeExceptionally(error);
                    firstToken.complete(null);
                }
            });
        } catch (RuntimeException e) {
            log.warn("流式调用发起失败，改用兜底文案 node={}", scope.nodeId(), e);
            return GuardedStream.NOT_STARTED;
        }

        try {
            firstToken.get(firstTokenTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (lock) {
                if (!started[0]) {
                    closed[0] = true;
                    log.warn("流式调用 {} ms 内没有输出，改用兜底文案 node={}", firstTokenTimeout.toMillis(), scope.nodeId());
                    return GuardedStream.NOT_STARTED;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return close(lock, started, closed);
        } catch (ExecutionException e) {
            // firstToken 只会正常完成，这里不会发生
        }

        int timeout = props.model().timeoutSeconds() == null ? 60 : props.model().timeoutSeconds();
        try {
            done.get(timeout + 10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return close(lock, started, closed);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("流式调用失败，改用兜底文案 node={}", scope.nodeId(), e instanceof ExecutionException ? e.getCause() : e);
            return close(lock, started, closed);
        }
        GuardedStream outcome = close(lock, started, closed);
        return outcome == GuardedStream.INTERRUPTED ? GuardedStream.COMPLETED : outcome;
    }

    /** 停止接收；返回值只区分"推出过内容"(INTERRUPTED) 和"什么都没推"(NOT_STARTED)，正常结束由调用方改写 */
    private static GuardedStream close(Object lock, boolean[] started, boolean[] closed) {
        synchronized (lock) {
            closed[0] = true;
            return started[0] ? GuardedStream.INTERRUPTED : GuardedStream.NOT_STARTED;
        }
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

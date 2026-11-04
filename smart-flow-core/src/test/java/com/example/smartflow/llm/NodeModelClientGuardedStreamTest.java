package com.example.smartflow.llm;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.llm.NodeModelClient.GuardedStream;
import com.example.smartflow.node.NodeScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 有兜底的流式调用：超时、出错时不能往前端漏内容，迟到的输出要丢掉。 */
class NodeModelClientGuardedStreamTest {

    private final List<TurnEvent> events = new CopyOnWriteArrayList<>();
    private final NodeScope scope = new NodeScope(new TurnContext("session", "turn", events::add), "exec", "probe");
    private final NodeModelClient client = new NodeModelClient(null, new FlowProperties(
            new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.0, 5, false), null, null),
            new ObjectMapper());
    private final ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("问一下"))).build();

    @Test
    void completedOutputIsAppendedOnANewLineAfterExistingText() {
        StringBuilder text = new StringBuilder();
        NodeModelClient.appendRoundText(scope, text, "当前已记录：阳光社区。");

        GuardedStream outcome = client.streamGuarded(scope, model(h -> {
            h.onPartialResponse("请问活动");
            h.onPartialResponse("面向哪些人？");
            h.onCompleteResponse(response("请问活动面向哪些人？"));
        }), request, null, text, Duration.ofSeconds(1));

        assertEquals(GuardedStream.COMPLETED, outcome);
        assertEquals("当前已记录：阳光社区。\n请问活动面向哪些人？", text.toString());
        assertEquals(text.toString(), tokens());
    }

    @Test
    void lateFirstTokenIsAbandonedAndNeverEmitted() throws Exception {
        StringBuilder text = new StringBuilder();
        GuardedStream outcome = client.streamGuarded(scope, model(h -> new Thread(() -> {
            sleep(300);
            h.onPartialResponse("迟到的追问");
            h.onCompleteResponse(response("迟到的追问"));
        }).start()), request, null, text, Duration.ofMillis(50));

        assertEquals(GuardedStream.NOT_STARTED, outcome);
        Thread.sleep(500);
        assertEquals("", text.toString());
        assertEquals("", tokens(), "放弃之后到达的内容不能推给前端");
    }

    @Test
    void errorBeforeFirstTokenEmitsNothing() {
        StringBuilder text = new StringBuilder();
        GuardedStream outcome = client.streamGuarded(scope,
                model(h -> h.onError(new RuntimeException("模型不可用"))), request, null, text, Duration.ofSeconds(1));
        assertEquals(GuardedStream.NOT_STARTED, outcome);
        assertEquals("", tokens());
    }

    @Test
    void completingWithoutAnyTokenCountsAsNotStarted() {
        StringBuilder text = new StringBuilder();
        GuardedStream outcome = client.streamGuarded(scope,
                model(h -> h.onCompleteResponse(response("只在最终响应里、没有流式输出"))),
                request, null, text, Duration.ofSeconds(1));
        assertEquals(GuardedStream.NOT_STARTED, outcome);
        assertEquals("", text.toString());
        assertEquals("", tokens());
    }

    @Test
    void errorAfterPartialOutputKeepsWhatWasAlreadySent() {
        StringBuilder text = new StringBuilder();
        GuardedStream outcome = client.streamGuarded(scope, model(h -> {
            h.onPartialResponse("方便说一下");
            h.onError(new RuntimeException("连接中断"));
        }), request, null, text, Duration.ofSeconds(1));
        assertEquals(GuardedStream.INTERRUPTED, outcome);
        assertEquals("方便说一下", text.toString());
        assertEquals("方便说一下", tokens());
    }

    @Test
    void exceptionWhenStartingIsReportedAsNotStarted() {
        GuardedStream outcome = client.streamGuarded(scope, model(h -> {
            throw new IllegalStateException("配置错误");
        }), request, null, new StringBuilder(), Duration.ofSeconds(1));
        assertEquals(GuardedStream.NOT_STARTED, outcome);
    }

    private String tokens() {
        return events.stream().filter(e -> e.type().equals("token"))
                .map(e -> (String) e.data().get("text")).reduce("", String::concat);
    }

    private static StreamingChatModel model(Consumer<StreamingChatResponseHandler> behavior) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
                behavior.accept(handler);
            }
        };
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.example.smartflow.node;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证实现委托给组件后，子类扩展点、异常契约和流式工具的追踪关联仍然生效。 */
class AbstractNodeDelegationTest {

    @Test
    void jsonRetryUsesOverriddenChatWithoutChangingOriginalMessages() {
        List<String> purposes = new ArrayList<>();
        List<ChatRequest> requests = new ArrayList<>();
        Deque<String> responses = new ArrayDeque<>(List.of("invalid", "```json\n{\"city\":\"杭州\"}\n```"));
        ProbeNode node = scriptedNode(responses, purposes, requests);
        List<ChatMessage> messages = List.of(UserMessage.from("返回 JSON"));

        Map<?, ?> result = node.chatJson(scope(new ArrayList<>()), "extract", null, messages, Map.class);

        assertEquals(Map.of("city", "杭州"), result);
        assertEquals(List.of("extract", "extract#retry"), purposes);
        assertEquals(messages, requests.getFirst().messages());
        assertEquals(3, requests.getLast().messages().size());
        assertInstanceOf(AiMessage.class, requests.getLast().messages().get(1));
        assertTrue(((UserMessage) requests.getLast().messages().getLast()).singleText().contains("合法的 JSON"));
        assertEquals(1, messages.size());
    }

    @Test
    void invalidJsonStillThrowsOriginalNodeException() {
        List<String> purposes = new ArrayList<>();
        ProbeNode node = scriptedNode(new ArrayDeque<>(List.of("invalid", "still invalid")),
                purposes, new ArrayList<>());

        var error = assertThrows(AbstractNode.LlmJsonException.class,
                () -> node.chatJson(scope(new ArrayList<>()), "extract", null,
                        List.of(UserMessage.from("返回 JSON")), Map.class));

        assertEquals(List.of("extract", "extract#retry"), purposes);
        assertTrue(error.getMessage().contains("purpose=extract, output=still invalid"));
        assertNotNull(error.getCause());
    }

    @Test
    void overriddenWindowControlsMessagesTextAndRememberedToolResults() {
        HistoryMessage selected = new HistoryMessage("user", "选中的消息", 1L, "selected");
        FlowState state = new FlowState(Map.of(
                FlowState.HISTORY, List.of(selected, new HistoryMessage("assistant", "最新消息", 2L, "latest")),
                FlowState.TOOL_FACTS, List.of(new ToolFact("selected", "lookup", "{}", "选中的结果"),
                        new ToolFact("latest", "lookup", "{}", "不应出现的结果"))));
        ProbeNode node = new ProbeNode(support(null, null)) {
            @Override
            public HistoryPolicy historyPolicy() {
                return super.historyPolicy().withToolResults(true);
            }

            @Override
            protected List<HistoryMessage> window(FlowState ignored, int limit) {
                return List.of(selected);
            }
        };

        assertEquals(List.of(UserMessage.from("选中的消息")), node.historyMessages(state));
        assertEquals(List.of(UserMessage.from("选中的消息")), node.historyMessages(state, 1));
        assertEquals("用户：选中的消息\n", node.historyAsText(state, 1));
        String memory = node.memoryBlock(state);
        assertTrue(memory.contains("选中的结果"));
        assertFalse(memory.contains("不应出现的结果"));
    }

    @Test
    void streamingAndToolLoopKeepTraceOverridesAndSeparateOutputBuffers() throws Exception {
        var ask = ToolExecutionRequest.builder().id("ask-1").name("ask_user")
                .arguments("{\"question\":\"哪个城市？\"}").build();
        Deque<AiMessage> responses = new ArrayDeque<>(List.of(AiMessage.from("普通回复"), AiMessage.from(ask)));
        List<ChatRequestOptions> receivedOptions = new ArrayList<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
                receivedOptions.add(options);
                AiMessage response = responses.removeFirst();
                if (response.text() != null) {
                    handler.onPartialResponse(response.text());
                }
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(response).build());
            }
        };
        List<String> toolLlmIds = new ArrayList<>();
        TraceRepository trace = new TraceRepository(null, null, null, null, new ObjectMapper()) {
            @Override
            public void insertToolCall(String toolCallId, String turnId, String sessionId, String execId,
                                       String llmCallId, String nodeId, String toolName, String argumentsJson) {
                toolLlmIds.add(llmCallId);
                assertEquals("probe", nodeId);
                assertEquals("ask_user", toolName);
            }

            @Override
            public void finishToolCall(String toolCallId, String result, String status, String errorMsg, long latencyMs) {
                assertEquals("SUCCESS", status);
            }
        };
        List<String> purposes = new ArrayList<>();
        List<String> callIds = new ArrayList<>();
        List<ChatRequestOptions> customizedOptions = new ArrayList<>();
        ProbeNode node = new ProbeNode(support(model, trace)) {
            @Override
            protected ChatRequestOptions traceOptions(NodeScope scope, String purpose, boolean streaming, String callId) {
                assertTrue(streaming);
                purposes.add(purpose);
                callIds.add(callId);
                ChatRequestOptions options = super.traceOptions(scope, purpose, streaming, callId);
                customizedOptions.add(options);
                return options;
            }
        };
        List<TurnEvent> events = new ArrayList<>();
        NodeScope scope = scope(events);
        List<ChatMessage> messages = List.of(UserMessage.from("查询天气"));

        assertEquals("普通回复", node.streamReply(scope, "reply", messages));
        var result = node.streamWithTools(scope, "answer", messages, List.of(), 4);

        assertTrue(result.asked());
        assertEquals("哪个城市？", result.text());
        assertEquals("哪个城市？", result.question());
        assertEquals(List.of("reply", "answer"), purposes);
        assertNull(callIds.getFirst());
        assertNotNull(callIds.getLast());
        assertEquals(List.of(callIds.getLast()), toolLlmIds);
        assertSame(customizedOptions.getFirst(), receivedOptions.getFirst());
        assertSame(customizedOptions.getLast(), receivedOptions.getLast());
        String tokens = events.stream().filter(e -> e.type().equals("token"))
                .map(e -> (String) e.data().get("text")).reduce("", String::concat);
        assertEquals("普通回复\n\n哪个城市？", tokens);
    }

    private ProbeNode scriptedNode(Deque<String> responses, List<String> purposes, List<ChatRequest> requests) {
        return new ProbeNode(support(null, null)) {
            @Override
            protected ChatResponse chat(NodeScope scope, String purpose, ChatModel model, ChatRequest request) {
                purposes.add(purpose);
                requests.add(request);
                return ChatResponse.builder().aiMessage(AiMessage.from(responses.removeFirst())).build();
            }
        };
    }

    private NodeSupport support(StreamingChatModel streaming, TraceRepository trace) {
        FlowProperties props = new FlowProperties(
                new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.0, 5, false),
                new FlowProperties.Memory(12), new FlowProperties.Sse(180));
        return new NodeSupport(new ModelRegistry(null, null, streaming), trace, new TurnRegistry(),
                new ObjectMapper(), props);
    }

    private NodeScope scope(List<TurnEvent> events) {
        return new NodeScope(new TurnContext("session", "turn", events::add), "exec", "probe");
    }

    private static class ProbeNode extends AbstractNode {
        ProbeNode(NodeSupport support) { super(support); }
        @Override public String id() { return "probe"; }
        @Override public NodeType type() { return NodeType.CHAT; }
        @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) { return Map.of(); }
    }
}

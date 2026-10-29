package com.example.smartflow.engine;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.config.GraphConfig;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.node.ChatNode;
import com.example.smartflow.node.ChooseTemplatePlaceholderNode;
import com.example.smartflow.node.CollectTargetNode;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.RouterNode;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.session.SessionState;
import com.example.smartflow.trace.TraceChatModelListener;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮场景测试：不依赖 Spring、数据库和真实模型。
 * <p>
 * 模型换成按脚本返回的 stub，用来验证"路由规则 + 任务栈 + 节点流转 + 流式输出 + 记录"这一整套编排逻辑。
 * 以后做 Evals 时，同样的场景脚本换成真实模型 + LLM 扮演用户，就是端到端评测。
 */
class WorkflowScenarioTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<String> routerScript = new ArrayDeque<>();
    private final Deque<String> extractScript = new ArrayDeque<>();
    private final List<String> llmCalls = new ArrayList<>();
    private final FakeSessions sessions = new FakeSessions();
    private final WorkflowEngine engine;

    WorkflowScenarioTest() throws Exception {
        FlowProperties props = new FlowProperties(
                new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.3, 5, false),
                new FlowProperties.Memory(12), new FlowProperties.Sse(180));
        FakeTrace trace = new FakeTrace();
        TraceChatModelListener listener = new TraceChatModelListener(trace, MAPPER);
        ModelRegistry models = new ModelRegistry(
                new ScriptedChatModel(List.of(listener)),
                new ScriptedChatModel(List.of(listener)),
                new StubStreamingModel(List.of(listener)));
        TurnRegistry turns = new TurnRegistry();
        NodeSupport support = new NodeSupport(models, trace, turns, MAPPER, props);
        var graph = new GraphConfig().workflowGraph(new RouterNode(support), new ChatNode(support),
                new CollectTargetNode(support), new ChooseTemplatePlaceholderNode(support));
        engine = new WorkflowEngine(graph, sessions, trace, turns, props);
    }

    @Test
    void createActivityWithDigression() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"想办活动\"}");
        extractScript.add("{}");
        turn("s1", "我想办一个活动", List.of("router", "collect_target"), "collect_target");

        // 大小写不规范 + 代码块包裹，也要能解析
        routerScript.add("{\"action\":\"continue\",\"intent\":\"activity_create\",\"reason\":\"回答年龄性别\"}");
        extractScript.add("```json\n{\"ageMin\":60,\"gender\":\"female\"}\n```");
        turn("s1", "60岁以上的女性", List.of("router", "collect_target"), "collect_target");

        // 插话：回答完追加任务恢复提示，任务保留
        routerScript.add("{\"action\":\"DIGRESS\",\"intent\":\"CHAT\",\"reason\":\"闲聊\"}");
        var digress = turn("s1", "今天天气怎么样", List.of("router", "chat"), "collect_target");
        assertTrue(digress.reply().endsWith("要继续吗？"), "插话后应提示继续任务");

        // 路由第一次输出非法 JSON，自动纠错重试；槽位收齐后同一轮进入 C2
        routerScript.add("not json");
        routerScript.add("{\"action\":\"CONTINUE\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"补充区域\"}");
        extractScript.add("{\"region\":\"阳光社区\"}");
        var done = turn("s1", "阳光社区", List.of("router", "collect_target", "choose_template"), "choose_template");
        assertTrue(done.reply().contains("60 岁及以上、女性、阳光社区"), "确认文案");
        assertEquals(60, done.state().getTaskStack().get(0).getSlots().get("ageMin"));
        assertTrue(llmCalls.contains("router/route#retry"), "应记录重试调用");
    }

    @Test
    void cancelActivity() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"\"}");
        extractScript.add("{}");
        turn("s2", "帮我创建活动", List.of("router", "collect_target"), "collect_target");

        routerScript.add("{\"action\":\"CANCEL\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"不办了\"}");
        var r = turn("s2", "算了不办了", List.of("router"), null);
        assertTrue(r.reply().contains("已为你取消"), "取消回复");
    }

    @Test
    void routerFallbackToChat() {
        routerScript.add("bad");
        routerScript.add("still bad");
        turn("s3", "你好", List.of("router", "chat"), null);
    }

    // ------------------------------------------------------------------ helpers

    private WorkflowEngine.TurnResult turn(String sid, String input, List<String> expectedPath, String expectedNode) {
        StringBuilder streamed = new StringBuilder();
        List<String> events = new ArrayList<>();
        var res = engine.handle(sid, input, e -> {
            events.add(e.type());
            if (e.type().equals("token")) {
                streamed.append(e.data().get("text"));
            }
        });
        assertEquals(expectedPath, res.nodePath());
        assertEquals(res.reply(), streamed.toString());
        assertEquals("turn", events.get(0));
        assertEquals("done", events.get(events.size() - 1));
        String current = res.state().getTaskStack().isEmpty() ? null : res.state().getTaskStack().get(0).getCurrentNode();
        assertTrue(Objects.equals(expectedNode, current), "任务当前节点 期望 " + expectedNode + " 实际 " + current);
        return res;
    }

    private static String systemText(ChatRequest r) {
        for (ChatMessage m : r.messages()) {
            if (m instanceof SystemMessage s) {
                return s.text();
            }
        }
        return "";
    }

    private class ScriptedChatModel implements ChatModel {
        private final List<ChatModelListener> listeners;

        ScriptedChatModel(List<ChatModelListener> listeners) { this.listeners = listeners; }

        @Override
        public List<ChatModelListener> listeners() { return listeners; }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            String sys = systemText(request);
            String out = sys.contains("路由模块") ? routerScript.pop()
                    : sys.contains("信息抽取模块") ? extractScript.pop() : "{}";
            return ChatResponse.builder().aiMessage(AiMessage.from(out)).tokenUsage(new TokenUsage(10, 5)).build();
        }
    }

    private static class StubStreamingModel implements StreamingChatModel {
        private final List<ChatModelListener> listeners;

        StubStreamingModel(List<ChatModelListener> listeners) { this.listeners = listeners; }

        @Override
        public List<ChatModelListener> listeners() { return listeners; }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            new Thread(() -> {
                handler.onPartialResponse("【流式");
                handler.onPartialResponse("回复】");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("【流式回复】")).build());
            }).start();
        }
    }

    /** 不落库，只校验写入的 JSON 合法，并记录模型调用 */
    private class FakeTrace extends TraceRepository {
        FakeTrace() { super(new JdbcTemplate(), MAPPER); }

        @Override public void insertTurn(String turnId, String sessionId, int seq, String userInput, Object stateBefore) { json(stateBefore); }
        @Override public void finishTurn(String turnId, String reply, String a, String t, List<String> path, Object after,
                                         String status, String err, long ms) { json(after); }
        @Override public void insertNodeExec(String execId, String turnId, String sessionId, int seq, String nodeId,
                                             String nodeType, Object input) { json(input); }
        @Override public void finishNodeExec(String execId, Object output, String status, String err, long ms) { json(output); }

        @Override
        public void insertLlmCall(String callId, String turnId, String sessionId, String execId, String nodeId, String purpose,
                                  String provider, String modelName, boolean streaming, String requestBodyJson) {
            llmCalls.add(nodeId + "/" + purpose);
            assertTrue(execId != null, "模型调用应关联到节点执行");
            readTree(requestBodyJson);
        }

        @Override
        public void finishLlmCall(String callId, String responseBodyJson, String responseText, Integer in, Integer out,
                                  Integer total, String finishReason, String status, String err, long ms) {
            assertEquals("SUCCESS", status);
            readTree(responseBodyJson);
        }

        private void readTree(String json) {
            try {
                MAPPER.readTree(json);
            } catch (Exception e) {
                throw new AssertionError("非法 JSON: " + json, e);
            }
        }
    }

    /** 内存版会话存储；状态经过 JSON 往返，模拟 JSONB */
    private static class FakeSessions extends SessionRepository {
        final Map<String, String> states = new HashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, List<HistoryMessage>> messages = new HashMap<>();

        FakeSessions() { super(new JdbcTemplate(), MAPPER); }

        @Override
        public void create(String id, String userId) {
            states.putIfAbsent(id, toJson(new SessionState()));
            counts.putIfAbsent(id, 0);
            messages.putIfAbsent(id, new ArrayList<>());
        }

        @Override
        public Optional<SessionRecord> find(String id) {
            try {
                return Optional.of(new SessionRecord(id, null, MAPPER.readValue(states.get(id), SessionState.class), counts.get(id)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void save(String id, SessionState state, int turnCount) {
            states.put(id, toJson(state));
            counts.put(id, turnCount);
        }

        @Override
        public void insertMessage(String id, String turnId, String role, String content, String nodeId) {
            messages.get(id).add(new HistoryMessage(role, content));
        }

        @Override
        public List<HistoryMessage> recentMessages(String id, int n) {
            List<HistoryMessage> all = messages.get(id);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }
}

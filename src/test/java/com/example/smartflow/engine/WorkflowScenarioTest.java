package com.example.smartflow.engine;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.config.GraphConfig;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.BizQueryNode;
import com.example.smartflow.node.ChatNode;
import com.example.smartflow.node.ChooseTemplatePlaceholderNode;
import com.example.smartflow.node.CollectTargetNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.RouterNode;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.session.SessionState;
import com.example.smartflow.tool.UserInfoTool;
import com.example.smartflow.tool.WeatherTool;
import com.example.smartflow.trace.TraceChatModelListener;
import com.example.smartflow.trace.TraceRepository;
import com.example.smartflow.web.NodeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private final List<String> routerPrompts = new ArrayList<>();
    private final Deque<String> toolScript = new ArrayDeque<>();
    private final Map<String, String> llmCallIds = new java.util.concurrent.ConcurrentHashMap<>();
    /** toolCallId → [工具名, 参数, 发起它的模型调用(节点/用途), 状态, 结果] */
    private final Map<String, List<String>> toolCalls = new java.util.LinkedHashMap<>();

    private final FlowProperties props = new FlowProperties(
            new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.3, 5, false),
            new FlowProperties.Memory(12), new FlowProperties.Sse(180));
    private final FakeTrace trace = new FakeTrace();
    private final TurnRegistry turns = new TurnRegistry();
    private final NodeSupport support;
    /** 和 Spring 一样：节点列表就是"注册了哪些节点"，路由目录和图都从它生成 */
    private final List<AbstractNode> nodes = new ArrayList<>();
    private final RouteCatalog catalog = new RouteCatalog(() -> nodes);
    private WorkflowEngine engine;

    WorkflowScenarioTest() throws Exception {
        TraceChatModelListener listener = new TraceChatModelListener(trace, MAPPER);
        ModelRegistry models = new ModelRegistry(
                new ScriptedChatModel(List.of(listener)),
                new ScriptedChatModel(List.of(listener)),
                new StubStreamingModel(List.of(listener)));
        support = new NodeSupport(models, trace, turns, MAPPER, props);
        nodes.addAll(List.of(new RouterNode(support, catalog), new ChatNode(support, catalog),
                new CollectTargetNode(support), new ChooseTemplatePlaceholderNode(support),
                new BizQueryNode(support, catalog, new WeatherTool(MAPPER), new UserInfoTool(MAPPER))));
        rebuild();
    }

    private void rebuild() throws Exception {
        engine = new WorkflowEngine(GraphConfig.build(nodes, catalog), sessions, trace, turns, props, catalog);
    }

    // =====================================================================
    // 新增子节点：只写子节点类，不碰 RouterNode
    // =====================================================================

    /** 模拟以后新增的 B4 节点：投诉建议（一次性节点） */
    static class ComplaintNode extends AbstractNode {
        ComplaintNode(NodeSupport support) { super(support); }
        @Override public String id() { return "complaint"; }
        @Override public NodeType type() { return NodeType.CHAT; }
        @Override public Optional<RouteSpec> routeSpec() {
            return Optional.of(RouteSpec.oneShot(FlowNames.ROUTER, "COMPLAINT", "投诉建议",
                    "用户要投诉、反映问题或提建议", List.of("楼道灯坏了没人修", "我要投诉物业"), 30));
        }
        @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) {
            return reply(emitText(scope, "已记录你的反馈。"));
        }
    }

    @Test
    void newChildNodeIsPickedUpWithoutChangingRouter() throws Exception {
        String before = ((RouterNode) nodes.get(0)).systemPrompt();
        assertFalse(before.contains("COMPLAINT"));

        nodes.add(new ComplaintNode(support));
        catalog.refresh();
        rebuild();

        String after = ((RouterNode) nodes.get(0)).systemPrompt();
        assertTrue(after.contains("- COMPLAINT（投诉建议）：用户要投诉、反映问题或提建议"), after);
        assertTrue(after.contains("「我要投诉物业」"), after);

        // 调试接口：新节点挂在 router 下，且已经写进了 router 的提示词
        Map<String, Object> view = new NodeController(catalog).nodes();
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) ((List<Map<String, Object>>) view.get("routers")).get(0).get("children");
        var complaint = children.stream().filter(c -> "complaint".equals(c.get("nodeId"))).findFirst().orElseThrow();
        assertEquals(true, complaint.get("inPrompt"));
        assertEquals("ONE_SHOT", complaint.get("kind"));
        assertTrue(children.stream().allMatch(c -> Boolean.TRUE.equals(c.get("inPrompt"))), "所有子节点都应出现在提示词中");
        assertEquals(List.of("choose_template"), view.get("unattached"));

        // 任务进行中投诉 → 去新节点，算插话，回答完提示继续任务
        routerScript.add("{\"action\":\"START\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"\"}");
        extractScript.add("{}");
        turn("s4", "我想办一个活动", List.of("router", "collect_target"), "collect_target");

        routerScript.add("{\"action\":\"DIGRESS\",\"intent\":\"COMPLAINT\",\"reason\":\"投诉\"}");
        var r = turn("s4", "对了楼道灯坏了", List.of("router", "complaint"), "collect_target");
        assertEquals("已记录你的反馈。\n\n对了，刚才的活动创建还没完成，要继续吗？", r.reply());
        assertTrue(routerPrompts.get(routerPrompts.size() - 1).contains("COMPLAINT"), "真正发给模型的提示词里有新节点");
    }

    // =====================================================================
    // B2 业务查询：工具调用
    // =====================================================================

    @Test
    void bizQueryCallsTools() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"查天气和个人信息\"}");
        toolScript.add("get_weather:{\"city\":\"杭州\"}|get_user_info:");
        var r = turn("b1", "杭州天气怎么样，顺便看看我叫什么", List.of("router", "biz_query"), null);

        // 两个工具都被调用、都成功，并且都关联到发起它们的那次模型调用
        assertEquals(2, toolCalls.size());
        var calls = new ArrayList<>(toolCalls.values());
        assertEquals(List.of("get_weather", "{\"city\":\"杭州\"}", "biz_query/answer", "SUCCESS"), calls.get(0).subList(0, 4));
        assertEquals(List.of("get_user_info", "{}", "biz_query/answer", "SUCCESS"), calls.get(1).subList(0, 4));
        assertTrue(calls.get(0).get(4).contains("\"city\":\"杭州\""), calls.get(0).get(4));
        assertTrue(calls.get(1).get(4).contains("\"name\":\"rise\"") && calls.get(1).get(4).contains("\"age\":27"));

        // 工具结果传回了模型，第二轮的回答里能看到；两轮文字之间换行
        assertTrue(r.reply().startsWith("我查一下。\n查询结果："), r.reply());
        assertTrue(r.reply().contains("rise") && r.reply().contains("杭州"), r.reply());
        assertTrue(llmCalls.contains("biz_query/answer#2"), "第二轮模型调用应被记录");
    }

    @Test
    void bizQueryDuringTaskIsDigression() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"\"}");
        extractScript.add("{}");
        turn("b2", "我想办一个活动", List.of("router", "collect_target"), "collect_target");

        // 模型说 START 也没关系：有任务时去一次性节点，代码会按插话处理，任务保留
        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"问个人信息\"}");
        toolScript.add("get_user_info:{}");
        var r = turn("b2", "我叫什么名字", List.of("router", "biz_query"), "collect_target");
        assertTrue(r.reply().contains("rise"), r.reply());
        assertTrue(r.reply().endsWith("对了，刚才的活动创建还没完成，要继续吗？"), r.reply());
    }

    @Test
    void unknownToolReturnsErrorToModel() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"\"}");
        toolScript.add("drop_database:{}");
        var r = turn("b3", "帮我删库", List.of("router", "biz_query"), null);
        var call = toolCalls.values().iterator().next();
        assertEquals("ERROR", call.get(3));
        assertTrue(r.reply().contains("工具执行失败"), "错误信息应传回模型: " + r.reply());
    }

    @Test
    void bizQueryIsRegisteredInRouterPrompt() {
        String prompt = ((RouterNode) nodes.get(0)).systemPrompt();
        assertTrue(prompt.contains("- BIZ_QUERY（业务查询）："), prompt);
        String tools = new BizQueryNode(support, catalog, new WeatherTool(MAPPER), new UserInfoTool(MAPPER))
                .promptPreview().get("answer · tools");
        assertTrue(tools.contains("get_weather") && tools.contains("city（必填）") && tools.contains("get_user_info"), tools);
    }

    @Test
    void unknownIntentGoesToFallback() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"NOT_EXIST\",\"reason\":\"\"}");
        turn("s5", "随便说点", List.of("router", "chat"), null);
    }

    @Test
    void invalidRouteConfigFailsFast() {
        nodes.add(new ChatNode(support, catalog) {
            @Override public String id() { return "chat2"; }   // 第二个兜底节点
        });
        catalog.refresh();
        try {
            catalog.validate();
            throw new AssertionError("应该校验失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("兜底"), expected.getMessage());
        }
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
            if (sys.contains("路由模块")) {
                routerPrompts.add(sys);
            }
            String out = sys.contains("路由模块") ? routerScript.pop()
                    : sys.contains("信息抽取模块") ? extractScript.pop() : "{}";
            return ChatResponse.builder().aiMessage(AiMessage.from(out)).tokenUsage(new TokenUsage(10, 5)).build();
        }
    }

    /**
     * 流式模型桩：
     * - 业务查询节点：本轮还没有工具结果时，按 toolScript 返回工具调用（"名字:参数JSON"，多个用 | 分隔）；
     *   有了工具结果后，返回把工具结果原样拼进去的文字，用来验证结果确实传回了模型
     * - 其他节点：固定返回【流式回复】
     */
    private class StubStreamingModel implements StreamingChatModel {
        private final List<ChatModelListener> listeners;

        StubStreamingModel(List<ChatModelListener> listeners) { this.listeners = listeners; }

        @Override
        public List<ChatModelListener> listeners() { return listeners; }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            new Thread(() -> {
                if (systemText(request).contains("业务查询模块")) {
                    List<String> results = request.messages().stream()
                            .filter(m -> m instanceof ToolExecutionResultMessage)
                            .map(m -> ((ToolExecutionResultMessage) m).text()).toList();
                    if (results.isEmpty()) {
                        assertTrue(!request.toolSpecifications().isEmpty(), "业务查询节点应把工具定义发给模型");
                        List<ToolExecutionRequest> calls = new ArrayList<>();
                        for (String c : toolScript.pop().split("\\|")) {
                            int i = c.indexOf(':');
                            calls.add(ToolExecutionRequest.builder().id("call_" + calls.size())
                                    .name(c.substring(0, i)).arguments(c.substring(i + 1)).build());
                        }
                        handler.onPartialResponse("我查一下。");
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("我查一下。", calls)).build());
                        return;
                    }
                    String text = "查询结果：" + String.join("；", results);
                    handler.onPartialResponse(text);
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
                    return;
                }
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
            llmCallIds.put(callId, nodeId + "/" + purpose);
            assertTrue(execId != null, "模型调用应关联到节点执行");
            readTree(requestBodyJson);
        }

        @Override
        public void finishLlmCall(String callId, String responseBodyJson, String responseText, Integer in, Integer out,
                                  Integer total, String finishReason, String status, String err, long ms) {
            assertEquals("SUCCESS", status);
            readTree(responseBodyJson);
        }

        @Override
        public void insertToolCall(String toolCallId, String turnId, String sessionId, String execId, String llmCallId,
                                   String nodeId, String toolName, String argumentsJson) {
            readTree(argumentsJson);
            toolCalls.put(toolCallId, new ArrayList<>(List.of(toolName, argumentsJson, String.valueOf(llmCallIds.get(llmCallId)))));
        }

        @Override
        public void finishToolCall(String toolCallId, String result, String status, String errorMsg, long latencyMs) {
            toolCalls.get(toolCallId).addAll(List.of(status, result));
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

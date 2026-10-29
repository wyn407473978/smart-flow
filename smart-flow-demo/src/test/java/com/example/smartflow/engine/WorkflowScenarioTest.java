package com.example.smartflow.engine;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.config.GraphConfig;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.memory.ConversationSummarizer;
import com.example.smartflow.memory.LongTermMemoryExtractor;
import com.example.smartflow.memory.MemoryRepository;
import com.example.smartflow.memory.MemoryRepository.SummaryRecord;
import com.example.smartflow.memory.MemoryService;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.demo.node.BizQueryNode;
import com.example.smartflow.demo.node.ChatNode;
import com.example.smartflow.demo.node.ChooseTemplatePlaceholderNode;
import com.example.smartflow.demo.node.CollectTargetNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.RouterNode;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.session.SessionState;
import com.example.smartflow.demo.tool.UserInfoTool;
import com.example.smartflow.demo.tool.WeatherTool;
import com.example.smartflow.trace.TraceChatModelListener;
import com.example.smartflow.trace.TraceRepository;
import com.example.smartflow.web.NodeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

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
    private final FakeMemoryRepository memoryRepo = new FakeMemoryRepository();
    /** 后台记忆任务（摘要 / 长期记忆）的模型输出脚本，没有脚本时摘要返回空、抽取返回空数组 */
    private final Deque<String> summaryScript = new ArrayDeque<>();
    private final Deque<String> memoryScript = new ArrayDeque<>();
    /** 各节点收到的系统提示词，按节点区分：chat / biz / ask */
    private final Map<String, List<String>> systemPrompts = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> routerPrompts = new ArrayList<>();
    private final Deque<String> toolScript = new ArrayDeque<>();
    private final List<String> routerInputs = new ArrayList<>();
    /** 后台记忆任务收到的用户消息（摘要的输入对话、抽取的本轮对话） */
    private final List<String> backgroundInputs = new ArrayList<>();
    /** 业务查询节点最终回答末尾追加的内容，用来模拟模型不听话、自己提醒继续任务 */
    private String finalSuffix = "";
    /** 业务查询节点收到的系统提示词 */
    private final List<String> bizPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, String> llmCallIds = new java.util.concurrent.ConcurrentHashMap<>();
    /** toolCallId → [工具名, 参数, 发起它的模型调用(节点/用途), 状态, 结果] */
    private final Map<String, List<String>> toolCalls = new java.util.LinkedHashMap<>();

    private FlowProperties props = new FlowProperties(
            new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.3, 5, false),
            new FlowProperties.Memory(12), new FlowProperties.Sse(180));
    private final FakeTrace trace = new FakeTrace();
    private final TurnRegistry turns = new TurnRegistry();
    private NodeSupport support;
    /** 和 Spring 一样：节点列表就是"注册了哪些节点"，路由目录和图都从它生成 */
    private final List<AbstractNode> nodes = new ArrayList<>();
    private final RouteCatalog catalog = new RouteCatalog(() -> nodes);
    private WorkflowEngine engine;

    private final ModelRegistry models;

    WorkflowScenarioTest() throws Exception {
        TraceChatModelListener listener = new TraceChatModelListener(trace, MAPPER);
        models = new ModelRegistry(
                new ScriptedChatModel(List.of(listener)),
                new ScriptedChatModel(List.of(listener)),
                new StubStreamingModel(List.of(listener)));
        useMemoryConfig(new FlowProperties.Memory(12));
    }

    /** 用指定的记忆配置重建节点和引擎（测试摘要阈值、token 上限时用） */
    private void useMemoryConfig(FlowProperties.Memory memoryConfig) throws Exception {
        props = new FlowProperties(props.model(), memoryConfig, props.sse());
        support = new NodeSupport(models, trace, turns, MAPPER, props);
        nodes.clear();
        nodes.addAll(List.of(new RouterNode(support, catalog), new ChatNode(support, catalog),
                new CollectTargetNode(support), new ChooseTemplatePlaceholderNode(support),
                new BizQueryNode(support, catalog, new WeatherTool(MAPPER), new UserInfoTool(MAPPER))));
        catalog.refresh();
        rebuild();
    }

    private void rebuild() throws Exception {
        MemoryService memory = new MemoryService(sessions, trace, memoryRepo, new ConversationSummarizer(),
                new LongTermMemoryExtractor(), support.models(), props, Runnable::run);
        engine = new WorkflowEngine(GraphConfig.build(nodes, catalog), sessions, trace, turns, props, catalog, memory);
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

    // =====================================================================
    // 追问：一次性节点向用户提问后，用户的回答要回到这个节点
    // =====================================================================

    private static final String ASK_CITY = "ask_user:{\"question\":\"请问还是查杭州今天的天气吗？\"}";

    /** 先开一个活动创建任务，再插话问天气，业务查询节点追问城市 */
    private void taskThenAskCity(String sid) {
        routerScript.add("{\"action\":\"START\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"\"}");
        extractScript.add("{}");
        turn(sid, "我想办一个活动", List.of("router", "collect_target"), "collect_target");

        routerScript.add("{\"action\":\"DIGRESS\",\"intent\":\"BIZ_QUERY\",\"reason\":\"查天气\"}");
        toolScript.add(ASK_CITY);
        var r = turn(sid, "今天天气怎么样", List.of("router", "biz_query"), "collect_target");

        // 追问时不追加"要继续吗"，一条回复里只有一个问题
        assertEquals("请问还是查杭州今天的天气吗？", r.reply());
        var stack = r.state().getTaskStack();
        assertEquals(2, stack.size());
        assertTrue(stack.get(1).isEphemeral(), "栈顶应是追问帧");
        assertEquals("biz_query", stack.get(1).getCurrentNode());
        assertEquals("请问还是查杭州今天的天气吗？", stack.get(1).getAwaiting());
    }

    /** 截图里的问题：用户回答"是的"是在回答追问，应该回到业务查询，而不是继续活动创建 */
    @Test
    void answerToPendingQuestionGoesBackToAskingNode() {
        taskThenAskCity("p1");

        routerScript.add("{\"action\":\"CONTINUE\",\"intent\":\"BIZ_QUERY\",\"reason\":\"回答追问\"}");
        toolScript.add("get_weather:{\"city\":\"杭州\"}");
        var r = turn("p1", "是的", List.of("router", "biz_query"), "collect_target");

        // 路由模型看得到这个追问
        assertTrue(routerInputs.get(routerInputs.size() - 1)
                .contains("待回答的问题：来自\"业务查询\"（BIZ_QUERY）：请问还是查杭州今天的天气吗？"), routerInputs.toString());
        // 查完天气，追问帧去掉，这时才提示继续活动创建
        assertTrue(r.reply().contains("\"city\":\"杭州\""), r.reply());
        assertTrue(r.reply().endsWith("对了，刚才的活动创建还没完成，要继续吗？"), r.reply());
        assertEquals(1, r.state().getTaskStack().size());
    }

    /** 截图里的问题：模型自己提醒了"活动创建还没完成，需要继续吗"，引擎不应再追加一遍 */
    @Test
    void noDuplicateReminderWhenModelAlreadyReminded() {
        taskThenAskCity("p6");

        // 节点提示词里明确要求不要自己提醒
        assertTrue(bizPrompts.get(bizPrompts.size() - 1).contains("不要提醒或询问用户是否继续那个任务"),
                bizPrompts.get(bizPrompts.size() - 1));

        // 但模型不一定听：模拟它在回答末尾自己提醒了
        finalSuffix = "\n\n刚才那个活动创建还没完成，需要继续吗？";
        routerScript.add("{\"action\":\"CONTINUE\",\"intent\":\"BIZ_QUERY\",\"reason\":\"回答追问\"}");
        toolScript.add("get_weather:{\"city\":\"杭州\"}");
        var r = turn("p6", "是的", List.of("router", "biz_query"), "collect_target");

        assertTrue(r.reply().endsWith("刚才那个活动创建还没完成，需要继续吗？"), r.reply());
        assertFalse(r.reply().contains("对了，刚才的活动创建还没完成"), "不应重复提醒: " + r.reply());
    }

    @Test
    void alreadyRemindedDetection() {
        assertTrue(WorkflowEngine.alreadyReminded("刚才那个活动创建还没完成，需要继续吗？", "活动创建"));
        assertTrue(WorkflowEngine.alreadyReminded("要继续刚才的活动创建吗", "活动创建"));
        // 只提到任务名、没问是否继续，不算提醒过
        assertFalse(WorkflowEngine.alreadyReminded("活动创建需要先选模版", "活动创建"));
        assertFalse(WorkflowEngine.alreadyReminded("要继续查别的城市吗", "活动创建"));
    }

    /** 用户没理追问，直接回答活动创建的问题：追问作废，继续任务 */
    @Test
    void pendingQuestionDroppedWhenUserContinuesTask() {
        taskThenAskCity("p2");

        routerScript.add("{\"action\":\"CONTINUE\",\"intent\":\"ACTIVITY_CREATE\",\"reason\":\"回答活动人群\"}");
        extractScript.add("{\"ageMin\":60,\"gender\":\"FEMALE\"}");
        var r = turn("p2", "60岁以上的女性", List.of("router", "collect_target"), "collect_target");
        assertEquals(1, r.state().getTaskStack().size());
        assertFalse(r.reply().contains("要继续吗"), r.reply());
    }

    /** 用户换了个话题：追问作废 */
    @Test
    void pendingQuestionDroppedOnNewTopic() {
        taskThenAskCity("p3");

        routerScript.add("{\"action\":\"DIGRESS\",\"intent\":\"CHAT\",\"reason\":\"闲聊\"}");
        var r = turn("p3", "你是谁", List.of("router", "chat"), "collect_target");
        assertEquals(1, r.state().getTaskStack().size());
        assertTrue(r.reply().endsWith("要继续吗？"), r.reply());
    }

    /** 没有任务、只有追问时用户说"不用了" */
    @Test
    void cancelPendingQuestionWithoutTask() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"\"}");
        toolScript.add(ASK_CITY);
        var asked = turn("p4", "今天天气怎么样", List.of("router", "biz_query"), "biz_query");
        assertEquals("请问还是查杭州今天的天气吗？", asked.reply());

        routerScript.add("{\"action\":\"CANCEL\",\"intent\":\"BIZ_QUERY\",\"reason\":\"不查了\"}");
        var r = turn("p4", "不用了", List.of("router"), null);
        assertTrue(r.reply().startsWith("好的，那先不查了"), r.reply());
        assertTrue(r.state().getTaskStack().isEmpty());
    }

    /** 路由模型输出不可用时，有追问优先回到追问的节点 */
    @Test
    void routerFallbackPrefersPendingQuestion() {
        taskThenAskCity("p5");

        routerScript.add("bad");
        routerScript.add("still bad");
        toolScript.add("get_weather:{\"city\":\"杭州\"}");
        turn("p5", "是的", List.of("router", "biz_query"), "collect_target");
    }

    @Test
    void bizQueryIsRegisteredInRouterPrompt() {
        String prompt = ((RouterNode) nodes.get(0)).systemPrompt();
        assertTrue(prompt.contains("- BIZ_QUERY（业务查询）："), prompt);
        String tools = new BizQueryNode(support, catalog, new WeatherTool(MAPPER), new UserInfoTool(MAPPER))
                .promptPreview().get("answer · tools");
        assertTrue(tools.contains("get_weather") && tools.contains("city（必填）") && tools.contains("get_user_info"), tools);
    }

    // =====================================================================
    // 记忆：滚动摘要 / token 窗口 / 工具结果 / 长期记忆
    // =====================================================================

    private static final String CHAT_START = "{\"action\":\"START\",\"intent\":\"CHAT\",\"reason\":\"闲聊\"}";

    private static String last(List<String> list) {
        return list.get(list.size() - 1);
    }

    @Test
    void rollingSummaryFoldsOldMessagesInBackground() throws Exception {
        // 未摘要的消息超过 6 条时，压缩到只剩最近 2 条
        useMemoryConfig(new FlowProperties.Memory(12, 3000, 60,
                new FlowProperties.Summary(true, 6, 2, 100), null));

        for (int i = 1; i <= 3; i++) {
            routerScript.add(CHAT_START);
            turn("m1", "第" + i + "句", List.of("router", "chat"), null);
        }
        // 3 轮 = 6 条消息，没超过阈值，不摘要
        assertTrue(backgroundInputs.isEmpty(), "还不应该触发摘要: " + backgroundInputs);

        routerScript.add(CHAT_START);
        summaryScript.add("用户和助手闲聊了三句。");
        turn("m1", "第4句", List.of("router", "chat"), null);
        // 第 4 轮结束后 8 条消息 > 6：把最早的 6 条（前 3 轮）压缩进摘要
        assertTrue(last(backgroundInputs).contains("用户：第1句") && last(backgroundInputs).contains("用户：第3句")
                && !last(backgroundInputs).contains("第4句"), last(backgroundInputs));
        SummaryRecord summary = memoryRepo.findSummary("m1");
        assertEquals("用户和助手闲聊了三句。", summary.summary());
        assertEquals(6L, summary.upToMessageId());
        assertEquals(6, summary.summarizedCount());

        // 下一轮：节点拿到摘要，历史里只剩摘要之后的原文
        routerScript.add(CHAT_START);
        turn("m1", "第5句", List.of("router", "chat"), null);
        String chatSys = last(systemPrompts.get("chat"));
        assertTrue(chatSys.contains("【早期对话摘要】\n用户和助手闲聊了三句。"), chatSys);
        assertTrue(last(routerInputs).contains("用户和助手闲聊了三句。"), "路由也应带上摘要");
        assertTrue(last(routerInputs).contains("用户：第4句") && !last(routerInputs).contains("第3句"), last(routerInputs));
    }

    @Test
    void emptySummaryDoesNotAdvance() throws Exception {
        useMemoryConfig(new FlowProperties.Memory(12, 3000, 60,
                new FlowProperties.Summary(true, 2, 0, 100), null));
        routerScript.add(CHAT_START);
        turn("m2", "你好", List.of("router", "chat"), null);
        routerScript.add(CHAT_START);
        turn("m2", "在吗", List.of("router", "chat"), null);
        // 摘要模型返回空（脚本为空）：不推进摘要位置，下次再试
        assertEquals(0L, memoryRepo.findSummary("m2").upToMessageId());
    }

    /** 直接调用 AbstractNode.window 验证按条数 + token 截断 */
    static class WindowProbe extends AbstractNode {
        WindowProbe(NodeSupport s) { super(s); }
        @Override public String id() { return "probe"; }
        @Override public NodeType type() { return NodeType.CHAT; }
        @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) { return Map.of(); }
        List<String> windowOf(List<String> contents, int limit) {
            List<HistoryMessage> h = contents.stream().map(c -> new HistoryMessage("user", c)).toList();
            return window(new FlowState(Map.of(FlowState.HISTORY, h)), limit).stream().map(HistoryMessage::content).toList();
        }
    }

    @Test
    void historyWindowRespectsTokenBudget() throws Exception {
        useMemoryConfig(new FlowProperties.Memory(12, 10, 60, null, null));
        var probe = new WindowProbe(support);
        List<String> h = List.of("一二三四五六", "七八九十", "甲乙丙");   // 6 / 4 / 3 token

        assertEquals(List.of("七八九十", "甲乙丙"), probe.windowOf(h, 12));   // 3+4=7，再加 6 超过 10
        assertEquals(List.of("甲乙丙"), probe.windowOf(h, 1));               // 条数限制
        useMemoryConfig(new FlowProperties.Memory(12, 2, 60, null, null));
        assertEquals(List.of("甲乙丙"), new WindowProbe(support).windowOf(h, 12)); // 最新一条超了也保留
    }

    @Test
    void toolResultsAreRememberedForQueryNode() {
        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"\"}");
        toolScript.add("get_weather:{\"city\":\"杭州\"}");
        turn("m3", "杭州今天天气怎么样", List.of("router", "biz_query"), null);
        assertFalse(last(bizPrompts).contains("【最近查询到的数据】"), "第一轮还没有历史数据");

        routerScript.add("{\"action\":\"START\",\"intent\":\"BIZ_QUERY\",\"reason\":\"追问\"}");
        toolScript.add("get_weather:{\"city\":\"杭州\",\"date\":\"2026-10-31\"}");
        turn("m3", "那明天呢", List.of("router", "biz_query"), null);
        String biz = last(bizPrompts);
        assertTrue(biz.contains("【最近查询到的数据】") && biz.contains("get_weather({\"city\":\"杭州\"}) → {"), biz);

        // 闲聊节点默认不带工具结果
        routerScript.add(CHAT_START);
        turn("m3", "谢谢", List.of("router", "chat"), null);
        assertFalse(last(systemPrompts.get("chat")).contains("【最近查询到的数据】"));
    }

    @Test
    void longTermMemoryIsExtractedAndUsedAcrossSessions() {
        routerScript.add(CHAT_START);
        memoryScript.add("{\"upserts\":[{\"key\":\"常住城市\",\"value\":\"杭州\"},{\"key\":\"称呼\",\"value\":\"小王\"},"
                + "{\"key\":\"\",\"value\":\"空 key 应被丢弃\"}],\"deletes\":[]}");
        turnAs("u1", "s-a", "我住在杭州，以后叫我小王", List.of("router", "chat"), null);
        assertEquals(Map.of("常住城市", "杭州", "称呼", "小王"), memoryRepo.memories.get("u1"));
        assertTrue(last(backgroundInputs).contains("用户：我住在杭州，以后叫我小王"), last(backgroundInputs));

        // 换一个会话，同一个用户：节点提示词里带上长期记忆，路由不带
        routerScript.add(CHAT_START);
        memoryScript.add("{\"upserts\":[],\"deletes\":[\"称呼\"]}");
        turnAs("u1", "s-b", "你好", List.of("router", "chat"), null);
        String chatSys = last(systemPrompts.get("chat"));
        assertTrue(chatSys.contains("【关于用户的长期信息】") && chatSys.contains("- 常住城市：杭州"), chatSys);
        assertFalse(last(routerInputs).contains("常住城市"), "路由不需要长期记忆");
        // 本轮抽取删除了"称呼"
        assertEquals(Map.of("常住城市", "杭州"), memoryRepo.memories.get("u1"));
    }

    @Test
    void sessionKeepsBoundUserAndNoUserMeansNoLongTermMemory() {
        // 会话第一次带 userId 绑定，之后不传也沿用
        routerScript.add(CHAT_START);
        memoryScript.add("{\"upserts\":[{\"key\":\"常住城市\",\"value\":\"北京\"}]}");
        turnAs("u2", "s-c", "我在北京", List.of("router", "chat"), null);
        routerScript.add(CHAT_START);
        turn("s-c", "你好", List.of("router", "chat"), null);
        assertTrue(last(systemPrompts.get("chat")).contains("- 常住城市：北京"), last(systemPrompts.get("chat")));

        // 从来没有 userId 的会话：不抽取长期记忆
        int before = backgroundInputs.size();
        routerScript.add(CHAT_START);
        turn("s-d", "我在上海", List.of("router", "chat"), null);
        assertEquals(before, backgroundInputs.size());
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
        return turnAs(null, sid, input, expectedPath, expectedNode);
    }

    private WorkflowEngine.TurnResult turnAs(String userId, String sid, String input,
                                             List<String> expectedPath, String expectedNode) {
        StringBuilder streamed = new StringBuilder();
        List<String> events = new ArrayList<>();
        var res = engine.handle(sid, userId, input, e -> {
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
                routerInputs.add(((UserMessage) request.messages().get(request.messages().size() - 1)).singleText());
            }
            String out = sys.contains("路由模块") ? routerScript.pop()
                    : sys.contains("信息抽取模块") ? extractScript.pop()
                    : sys.contains("对话摘要模块") ? (summaryScript.isEmpty() ? "" : summaryScript.pop())
                    : sys.contains("长期记忆模块") ? (memoryScript.isEmpty() ? "{\"upserts\":[],\"deletes\":[]}" : memoryScript.pop())
                    : "{}";
            if (sys.contains("对话摘要模块") || sys.contains("长期记忆模块")) {
                backgroundInputs.add(((UserMessage) request.messages().get(request.messages().size() - 1)).singleText());
            }
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
                String sysText = systemText(request);
                String who = sysText.contains("业务查询模块") ? "biz" : sysText.contains("正在帮用户创建活动") ? "ask" : "chat";
                systemPrompts.computeIfAbsent(who, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(sysText);
                if (sysText.contains("业务查询模块")) {
                    bizPrompts.add(sysText);
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
                        boolean asking = calls.stream().anyMatch(c -> c.name().equals("ask_user"));
                        String preamble = asking ? null : "我查一下。";
                        if (preamble != null) {
                            handler.onPartialResponse(preamble);
                        }
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(preamble == null ? AiMessage.from(calls) : AiMessage.from(preamble, calls)).build());
                        return;
                    }
                    String text = "查询结果：" + String.join("；", results) + finalSuffix;
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
        FakeTrace() { super(null, MAPPER); }

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
            assertTrue(execId != null || MemoryService.TRACE_NODE_ID.equals(nodeId), "模型调用应关联到节点执行");
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
            toolTurns.put(toolCallId, turnId);
        }

        final Map<String, String> toolTurns = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<ToolFact> listToolFacts(String sessionId, java.util.Collection<String> turnIds) {
            List<ToolFact> out = new ArrayList<>();
            toolCalls.forEach((id, c) -> {
                String turnId = toolTurns.get(id);
                if (turnIds.contains(turnId) && c.size() >= 5 && "SUCCESS".equals(c.get(3)) && !"ask_user".equals(c.get(0))) {
                    out.add(new ToolFact(turnId, c.get(0), c.get(1), c.get(4)));
                }
            });
            return out;
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

    /** 内存版记忆存储 */
    private static class FakeMemoryRepository extends MemoryRepository {
        final Map<String, SummaryRecord> summaries = new HashMap<>();
        final Map<String, Map<String, String>> memories = new HashMap<>();

        FakeMemoryRepository() { super(null); }

        @Override public SummaryRecord findSummary(String sessionId) { return summaries.getOrDefault(sessionId, SummaryRecord.EMPTY); }
        @Override public void saveSummary(String sessionId, SummaryRecord record) { summaries.put(sessionId, record); }

        @Override
        public List<MemoryFact> listUserMemories(String userId, int limit) {
            List<MemoryFact> out = new ArrayList<>();
            memories.getOrDefault(userId, Map.of()).forEach((k, v) -> out.add(new MemoryFact((long) out.size() + 1, k, v, "")));
            return out.subList(0, Math.min(limit, out.size()));
        }

        @Override
        public void upsertUserMemory(String userId, String key, String value, String sessionId, String turnId) {
            memories.computeIfAbsent(userId, k -> new java.util.LinkedHashMap<>()).put(key, value);
        }

        @Override
        public void deleteUserMemoryByKey(String userId, String key) {
            memories.getOrDefault(userId, new HashMap<>()).remove(key);
        }
    }

    /** 内存版会话存储；状态经过 JSON 往返，模拟 JSONB */
    private static class FakeSessions extends SessionRepository {
        final Map<String, String> states = new HashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, List<HistoryMessage>> messages = new HashMap<>();
        final Map<String, String> users = new HashMap<>();
        long nextId = 1;

        FakeSessions() { super(null, MAPPER); }

        @Override
        public void create(String id, String userId) {
            states.putIfAbsent(id, toJson(new SessionState()));
            counts.putIfAbsent(id, 0);
            messages.putIfAbsent(id, new ArrayList<>());
            if (userId != null) {
                users.putIfAbsent(id, userId);
            }
        }

        @Override
        public Optional<SessionRecord> find(String id) {
            try {
                return Optional.of(new SessionRecord(id, users.get(id), MAPPER.readValue(states.get(id), SessionState.class), counts.get(id)));
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
            messages.get(id).add(new HistoryMessage(role, content, nextId++, turnId));
        }

        @Override
        public List<HistoryMessage> messagesAfter(String id, long afterId, int limit) {
            List<HistoryMessage> after = messages.getOrDefault(id, List.of()).stream().filter(m -> m.id() > afterId).toList();
            return new ArrayList<>(after.subList(Math.max(0, after.size() - limit), after.size()));
        }

        @Override
        public List<HistoryMessage> recentMessages(String id, int n) {
            List<HistoryMessage> all = messages.get(id);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }
}

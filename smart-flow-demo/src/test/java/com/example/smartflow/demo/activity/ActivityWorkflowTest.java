package com.example.smartflow.demo.activity;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.config.GraphConfig;
import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.demo.node.BizQueryNode;
import com.example.smartflow.demo.node.ChatNode;
import com.example.smartflow.demo.tool.UserInfoTool;
import com.example.smartflow.demo.tool.WeatherTool;
import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.flow.*;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.node.*;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.trace.TraceRepository;
import com.example.smartflow.web.NodeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用实际图、根路由和所有业务节点跑多轮；模型按脚本返回，无数据库和真实模型依赖。 */
class ActivityWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String THREE = "{\"templates\":[\"社区健康讲座欢迎参加。\",\"邀您参与社区健康讲座。\",\"社区健康讲座活动通知。\"]}";
    private final Deque<String> roots = new ArrayDeque<>(), inputs = new ArrayDeque<>(), templates = new ArrayDeque<>(), confirms = new ArrayDeque<>();
    private final List<String> inputPrompts = new ArrayList<>();
    private final List<AbstractNode> nodes = new ArrayList<>();
    private final TurnRegistry turns = new TurnRegistry();
    private final RouteCatalog catalog = new RouteCatalog(() -> nodes);
    private final MutableClock clock = new MutableClock();
    private final RecordingClient client = new RecordingClient();
    private final NodeSupport support;
    private final CompiledGraph<FlowState> graph;
    private List<TaskFrame> tasks = new ArrayList<>();
    private List<String> path = List.of();
    private final List<HistoryMessage> history = new ArrayList<>();

    ActivityWorkflowTest() throws Exception {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                String system = request.messages().stream().filter(SystemMessage.class::isInstance)
                        .map(SystemMessage.class::cast).map(SystemMessage::text).findFirst().orElse("");
                String output;
                if (system.contains("短信活动创建路由")) {
                    inputPrompts.add(request.messages().toString());
                    output = inputs.remove();
                } else if (system.contains("社区短信文案助手")) output = templates.remove();
                else if (system.contains("确认判断模块")) output = confirms.remove();
                else output = roots.remove();
                return ChatResponse.builder().aiMessage(AiMessage.from(output)).build();
            }
        };
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("保留的业务回复");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("保留的业务回复")).build());
            }
        };
        TraceRepository trace = new TraceRepository(null, null, null, null, JSON) {
            @Override public void insertNodeExec(String e, String t, String s, int n, String id, String type, Object in) { }
            @Override public void finishNodeExec(String e, Object output, String status, String error, long ms) { }
        };
        FlowProperties props = new FlowProperties(new FlowProperties.Model("http://stub", "stub", "chat", "router", 0.0, 5, false),
                new FlowProperties.Memory(12), new FlowProperties.Sse(180));
        support = new NodeSupport(new ModelRegistry(model, model, streaming), trace, turns, JSON, props);
        List<ActivityCollectionNode> children = List.of(new ActivityRegionNode(support), new ActivityAudienceNode(support),
                new ActivityTimeNode(support, clock), new ActivityTemplateNode(support));
        nodes.add(new RouterNode(support, catalog));
        nodes.add(new ChatNode(support, catalog));
        nodes.add(new BizQueryNode(support, catalog, new WeatherTool(JSON), new UserInfoTool(JSON)));
        nodes.add(new ActivityCreateRouterNode(support, catalog, children, clock));
        nodes.addAll(children);
        nodes.add(new ActivitySubmitNode(support, client, clock));
        var legacy = new ActivityLegacyConfiguration();
        nodes.addAll(List.of(legacy.legacyActivityCollect(support), legacy.legacyActivityTemplate(support), legacy.legacyActivitySubmit(support)));
        graph = GraphConfig.build(nodes, catalog);
    }

    private String turn(String message, String extracted) throws Exception {
        roots.add("{\"action\":\"" + (tasks.isEmpty() ? "START" : "CONTINUE") + "\",\"intent\":\"ACTIVITY_CREATE\"}");
        if (extracted != null) inputs.add(extracted);
        return run(message);
    }

    private String run(String message) throws Exception {
        String turnId = UUID.randomUUID().toString();
        List<TurnEvent> events = new ArrayList<>();
        TurnContext context = new TurnContext("sms-test", turnId, events::add);
        turns.register(context);
        try {
            FlowState last = null;
            for (var output : graph.stream(Map.of(FlowState.SESSION_ID, "sms-test", FlowState.TURN_ID, turnId,
                    FlowState.USER_INPUT, message, FlowState.TASK_STACK, new ArrayList<>(tasks), FlowState.HISTORY, new ArrayList<>(history))))
                last = output.state();
            assertNotNull(last);
            path = context.nodePath();
            String reply = String.join("\n\n", last.replies());
            // 模拟会话持久化的 JSON 往返，不能依赖 JVM 中的对象类型和引用残留。
            String saved = JSON.writeValueAsString(last.taskStack().stream().filter(TaskFrame::isOpen).toList());
            tasks = JSON.readValue(saved, JSON.getTypeFactory().constructCollectionType(ArrayList.class, TaskFrame.class));
            history.add(new HistoryMessage(HistoryMessage.USER, message));
            history.add(new HistoryMessage(HistoryMessage.ASSISTANT, reply));
            return reply;
        } finally { turns.unregister(turnId); }
    }

    private void untilConfirmation() throws Exception {
        templates.add(THREE);
        turn("明天九点发给蒋村街道20岁以上男性，通知健康讲座", """
                {"region":"杭州市西湖区蒋村街道","gender":"MALE","ageMin":20,
                 "sendTime":"2026-11-05 09:00:00","templateBrief":"健康讲座"}
                """);
        String reply = turn("选第二个", "{\"templateChoice\":2}");
        assertTrue(reply.contains("请确认是否"), reply);
        assertEquals(MinshengNames.ACTIVITY_SUBMIT, tasks.getFirst().getCurrentNode());
        assertEquals(0, client.calls);
    }

    @Test void collectsAllFieldsInOneMessageButRequiresTemplateChoiceAndFinalConfirmation() throws Exception {
        untilConfirmation();
        assertTrue(inputPrompts.getFirst().contains("2026-11-04 10:00:00"));
        String reply = turn("确认", null);
        assertTrue(reply.contains("短信活动创建完毕"), reply);
        assertTrue(tasks.isEmpty());
        assertEquals(1, client.calls);
        assertEquals("杭州市西湖区蒋村街道", client.request.region());
        assertEquals("MALE", client.request.gender());
        assertEquals(20, client.request.ageMin());
        assertEquals("2026-11-05 09:00:00", client.request.sendTime());
        assertEquals("邀您参与社区健康讲座。", client.request.smsContent());
        assertEquals(List.of("router", "activity_submit"), path);
    }

    @Test void acceptsAllTwentyFourCollectionOrders() throws Exception {
        for (List<String> order : permutations(new ArrayList<>(ActivityData.SECTIONS))) {
            ActivityWorkflowTest scenario = new ActivityWorkflowTest();
            for (String section : order) {
                String input = switch (section) {
                    case "region" -> "{\"region\":\"杭州市西湖区蒋春街道\"}";
                    case "audience" -> "{\"allAudience\":true}";
                    case "time" -> "{\"sendTime\":\"2026-11-05 09:00:00\"}";
                    default -> "{\"templateBrief\":\"健康讲座\"}";
                };
                if (section.equals("template")) scenario.templates.add(THREE);
                scenario.turn(section, input);
                if (section.equals("template")) scenario.turn("选第一个", "{\"templateChoice\":1}");
            }
            assertEquals(MinshengNames.ACTIVITY_SUBMIT, scenario.tasks.getFirst().getCurrentNode(), order.toString());
            assertEquals("杭州市西湖区蒋春街道", scenario.tasks.getFirst().getSlots().get("region"));
            assertEquals("全部人群", ActivityData.audience(scenario.tasks.getFirst().getSlots()));
            assertEquals(0, scenario.client.calls);
        }
    }

    @Test void invalidPastAmbiguousAndEqualTimesNeverBecomeReady() throws Exception {
        for (String time : List.of("2026-11-04 09:00:00", "2026-11-04 10:00:00", "2026-02-30 12:00:00", "", "明天")) {
            String reply = turn("发送时间", JSON.writeValueAsString(Map.of("sendTime", time)));
            assertTrue(reply.contains("有效的未来时间"), reply);
            assertFalse(tasks.getFirst().getSlots().containsKey("sendTime"));
            assertEquals(0, client.calls);
        }
    }

    @Test void confirmationRechecksTimeAndRequiresNewConfirmationAfterCorrection() throws Exception {
        untilConfirmation();
        clock.instant = Instant.parse("2026-11-05T01:00:01Z");
        String expired = turn("确认", "{}");
        assertTrue(expired.contains("未来的短信发送日期"), expired);
        assertEquals(0, client.calls);
        String corrected = turn("改为后天九点", "{\"sendTime\":\"2026-11-07 09:00:00\"}");
        assertTrue(corrected.contains("请确认是否"), corrected);
        assertEquals(0, client.calls);
        turn("确认", null);
        assertEquals(1, client.calls);
    }

    @Test void partialTimeAnswersIncludePreviousUserDateInTheNextExtraction() throws Exception {
        turn("明天发送", "{\"sendTime\":\"\"}");
        turn("上午9点", "{\"sendTime\":\"2026-11-05 09:00:00\"}");
        assertTrue(inputPrompts.getLast().contains("明天发送"));
        assertEquals("2026-11-05 09:00:00", tasks.getFirst().getSlots().get("sendTime"));
    }

    @Test void editsAtConfirmationKeepOtherParametersAndDoNotSubmit() throws Exception {
        untilConfirmation();
        confirms.add("{\"decision\":\"MODIFY\"}");
        String edited = turn("区域改为拱墅区", "{\"region\":\"杭州市拱墅区\",\"editSections\":[\"region\"]}");
        assertTrue(edited.contains("杭州市拱墅区"), edited);
        assertTrue(edited.contains("请确认是否"), edited);
        assertEquals("邀您参与社区健康讲座。", tasks.getFirst().getSlots().get("smsContent"));
        assertEquals(0, client.calls);
        confirms.add("{\"decision\":\"MODIFY\"}");
        String ask = turn("我要修改区域", "{\"editSections\":[\"region\"]}");
        assertTrue(ask.contains("短信要发到哪个区域"), ask);
        assertEquals("杭州市拱墅区", tasks.getFirst().getSlots().get("region"));
        turn("还没想好", "{}");
        assertFalse(ActivityData.ready(tasks.getFirst(), clock));
    }

    @Test void templateReplacementInvalidatesOldChoiceAndRejectsInvalidChoice() throws Exception {
        untilConfirmation();
        confirms.add("{\"decision\":\"MODIFY\"}");
        templates.add("{\"templates\":[\"停水通知一\",\"停水通知二\",\"停水通知三\"]}");
        String reply = turn("改成停水通知，选第一个", "{\"templateBrief\":\"停水通知\",\"templateChoice\":1}");
        assertTrue(reply.contains("停水通知三"), reply);
        assertFalse(tasks.getFirst().getSlots().containsKey("smsContent"));
        String invalid = turn("选第四个", "{\"templateChoice\":4}");
        assertTrue(invalid.contains("选择1、2或3"), invalid);
        assertEquals(0, client.calls);
        turn("选第三个", "{\"templateChoice\":3}");
        assertEquals("停水通知三", tasks.getFirst().getSlots().get("smsContent"));
    }

    @Test void mockFailuresCanRetryWithSameIdempotencyKey() throws Exception {
        untilConfirmation();
        client.fail = true;
        assertTrue(turn("确认", null).contains("重试"));
        assertFalse(tasks.isEmpty());
        client.fail = false;
        assertTrue(turn("确认", null).contains("创建完毕"));
        assertEquals(client.keys.get(0), client.keys.get(1));
        var again = client.create(client.keys.getFirst(), client.request);
        assertEquals(client.result.activityId(), again.activityId());
        assertTrue(tasks.isEmpty());
    }

    @Test void keepsChatAndBusinessQueriesAndAllowsCancellationDuringCollection() throws Exception {
        turn("创建短信活动", "{}");
        String id = tasks.getFirst().getTaskId();
        for (String intent : List.of("CHAT", "BIZ_QUERY")) {
            roots.add("{\"action\":\"DIGRESS\",\"intent\":\"" + intent + "\"}");
            assertTrue(run("插话查询").contains("保留的业务回复"));
            assertEquals(id, tasks.getFirst().getTaskId());
            assertEquals(intent.equals("CHAT") ? "chat" : "biz_query", path.getLast());
        }
        turn("全体人群", "{\"allAudience\":true}");
        assertEquals("全部人群", ActivityData.audience(tasks.getFirst().getSlots()));
        roots.add("{\"action\":\"CANCEL\",\"intent\":\"ACTIVITY_CREATE\"}");
        assertTrue(run("取消").contains("取消"));
        assertTrue(tasks.isEmpty());
        assertEquals(0, client.calls);
        String view = JSON.writeValueAsString(new NodeController(catalog).nodes());
        assertTrue(view.contains("ACTIVITY_CREATE"));
        for (String child : List.of("activity_region", "activity_audience", "activity_time", "activity_template"))
            assertTrue(view.contains(child));
    }

    @Test void malformedRouterAndRecommendationResultsKeepTaskOpen() throws Exception {
        inputs.add("invalid");
        String reply = turn("创建短信活动", "invalid again");
        assertTrue(reply.contains("未能识别"), reply);
        templates.add("{\"templates\":[\"重复\",\"重复\",\"重复\"]}");
        String failure = turn("健康讲座短信", "{\"templateBrief\":\"健康讲座\"}");
        assertTrue(failure.contains("三个有效模版"), failure);
        assertFalse(tasks.getFirst().getSlots().containsKey("smsContent"));
        assertEquals(0, client.calls);
    }

    @Test void audienceValidationIsAtomicAndAllAudienceClearsOldFilters() {
        var node = new ActivityAudienceNode(support);
        TaskFrame task = TaskFrame.start("ACTIVITY_CREATE", "ACTIVITY_CREATE");
        assertNull(node.collect(task, new ActivityInput(null, null, "MALE", 20, 50, null, null, null, null), null));
        assertNotNull(node.collect(task, new ActivityInput(null, null, "FEMALE", 80, 30, null, null, null, null), null));
        assertEquals("MALE", task.getSlots().get("gender"));
        assertEquals(20, task.getSlots().get("ageMin"));
        node.collect(task, new ActivityInput(null, null, null, 60, null, null, null, null, null), null);
        assertFalse(task.getSlots().containsKey("ageMax"));
        node.collect(task, new ActivityInput(null, true, null, null, null, null, null, null, null), null);
        assertEquals("全部人群", ActivityData.audience(task.getSlots()));
        assertFalse(task.getSlots().containsKey("ageMin"));
    }

    @Test void oldSessionsKeepTheirParametersButMustMeetNewSmsRequirements() throws Exception {
        for (String oldId : List.of("collect_target", "choose_template", "submit_activity")) {
            TaskFrame task = TaskFrame.start("ACTIVITY_CREATE", oldId);
            task.getSlots().putAll(Map.of("region", "阳光社区", "gender", "FEMALE", "ageMin", 60,
                    "activityTime", "下周六上午", "template", "ELDERLY_CARE"));
            task.getEditingSlots().add("ageMin");
            tasks = new ArrayList<>(List.of(task));
            turn("继续", "{}");
            assertEquals(task.getTaskId(), tasks.getFirst().getTaskId());
            assertEquals("阳光社区", tasks.getFirst().getSlots().get("region"));
            assertTrue(tasks.getFirst().getEditingSlots().contains("audience"));
            assertEquals(MinshengNames.ACTIVITY_CREATE, tasks.getFirst().getCurrentNode());
            assertFalse(ActivityData.ready(tasks.getFirst(), clock));
        }
        assertEquals(0, client.calls);
    }

    @Test void springRegistersOnlyNewWorkflowAndLegacyRedirects() throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(NodeSupport.class, () -> support);
            context.registerBean(RouteCatalog.class, () -> new RouteCatalog(context.getBeanProvider(AbstractNode.class)));
            context.registerBean(RouterNode.class, () -> new RouterNode(support, context.getBean(RouteCatalog.class)));
            context.registerBean(ChatNode.class, () -> new ChatNode(support, context.getBean(RouteCatalog.class)));
            context.registerBean(BizQueryNode.class, () -> new BizQueryNode(support, context.getBean(RouteCatalog.class), new WeatherTool(JSON), new UserInfoTool(JSON)));
            context.scan("com.example.smartflow.demo.activity");
            context.refresh();
            var registered = new ArrayList<>(context.getBeansOfType(AbstractNode.class).values());
            var routes = context.getBean(RouteCatalog.class);
            GraphConfig.build(registered, routes);
            assertEquals(3, routes.options("router").size());
            assertEquals(5, routes.options(MinshengNames.ACTIVITY_CREATE).size());
            assertEquals(1, routes.options("router").stream().filter(o -> o.spec().isTaskEntry()).count());
            assertFalse(registered.stream().anyMatch(n -> n.getClass().getSimpleName().equals("CollectTargetNode")));
        }
    }

    private static List<List<String>> permutations(List<String> source) {
        if (source.isEmpty()) return List.of(List.of());
        List<List<String>> out = new ArrayList<>();
        for (String first : source) {
            List<String> rest = new ArrayList<>(source); rest.remove(first);
            for (List<String> tail : permutations(rest)) {
                List<String> order = new ArrayList<>(); order.add(first); order.addAll(tail); out.add(order);
            }
        }
        return out;
    }

    private static class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-11-04T02:00:00Z");
        @Override public ZoneId getZone() { return ActivityData.ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }

    private static class RecordingClient extends MockSmsActivityClient {
        int calls;
        boolean fail;
        Request request;
        Result result;
        List<String> keys = new ArrayList<>();
        @Override public Result create(String key, Request request) {
            calls++; keys.add(key); this.request = request;
            if (fail) throw new IllegalStateException("mock接口失败");
            return result = super.create(key, request);
        }
    }
}

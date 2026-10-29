package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.tool.FlowTool;
import com.example.smartflow.tool.UserInfoTool;
import com.example.smartflow.tool.WeatherTool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B2：业务查询。挂载工具，由模型决定调用哪个工具、传什么参数，拿到结果后组织语言回复用户。
 * <p>
 * 加新工具：写一个 {@link FlowTool} 实现类，注入到这里加进 {@link #tools} 即可。
 * 如果新工具扩大了这个节点的能力范围，记得同步更新 {@link #routeSpec()} 的描述和示例，路由才知道该把这类问题发过来。
 */
@Component
public class BizQueryNode extends AbstractNode {

    /** 一轮对话里最多允许几轮工具调用 */
    private static final int MAX_TOOL_ROUNDS = 4;

    private static final String SYSTEM_PROMPT = """
            你是"智慧民生"助手的业务查询模块，负责通过工具查询业务数据并回答用户。用中文回答，语气亲切、简洁。

            今天是 %s。

            规则：
            1. 需要数据时必须调用工具获取，不要凭记忆或猜测回答，不要编造工具没有返回的信息。
            2. 缺少必要参数（例如查天气没说城市），或者需要用户确认（例如"还是查杭州吗"）时，调用 ask_user 工具提问，
               不要自己假设，也不要在普通回复里直接提问。
            3. 工具返回错误时，如实告诉用户暂时查询失败，不要编造结果。
            4. 拿到结果后直接回答用户的问题，不要提到"工具""接口""JSON"这类技术词。
            5. 超出可用工具范围的问题，说明目前还不支持查询。
            """;

    private static final String HAS_ACTIVE_TASK = """

            用户当前有一个进行中的"%s"，这次只是临时查询别的信息。只回答本次查询，不要替用户填写那个任务的任何参数。
            也不要提醒或询问用户是否继续那个任务，系统会在你回答之后统一提醒，你再提一遍会重复。
            """;

    private final List<FlowTool> tools;
    private final RouteCatalog catalog;

    public BizQueryNode(NodeSupport support, RouteCatalog catalog,
                        WeatherTool weatherTool, UserInfoTool userInfoTool) {
        super(support);
        this.catalog = catalog;
        this.tools = List.of(weatherTool, userInfoTool);
    }

    @Override
    public String id() {
        return FlowNames.BIZ_QUERY;
    }

    @Override
    public NodeType type() {
        return NodeType.TOOL_AGENT;
    }

    @Override
    public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.oneShot(FlowNames.ROUTER, "BIZ_QUERY", "业务查询",
                "需要查询业务数据才能回答的问题：查询某个城市的天气；查询用户本人的个人信息（姓名、年龄）",
                List.of("今天北京天气怎么样", "明天要带伞吗", "我叫什么名字", "查一下我的个人信息"),
                20));
    }

    /** 查询类节点带上最近的工具调用结果，用户追问"那明天呢""换成北京呢"时模型能看到上一轮查到的数据 */
    @Override
    public HistoryPolicy historyPolicy() {
        return super.historyPolicy().withToolResults(true);
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("answer · system", systemPrompt(null)
                + "\n{记忆块：长期记忆 / 早期对话摘要 / 最近查询到的数据，没有则不出现}");
        prompts.put("answer · tools", describeTools(tools));
        return prompts;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        String taskName = state.activeTask().map(t -> catalog.taskName(t.getTaskType())).orElse(null);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system(systemPrompt(taskName) + memoryBlock(state)));
        messages.addAll(historyMessages(state));
        messages.add(UserMessage.from(state.userInput()));

        ToolLoopResult result = streamWithTools(scope, "answer", messages, tools, MAX_TOOL_ROUNDS);
        Map<String, Object> updates = reply(result.text());
        if (result.asked()) {
            // 追问了用户（例如问城市），下一轮用户的回答会被送回这里
            awaitAnswer(state, updates, result.question());
        } else {
            closeAwait(state, updates);
        }
        return updates;
    }

    private String systemPrompt(String activeTaskName) {
        String sys = SYSTEM_PROMPT.formatted(LocalDate.now());
        return activeTaskName == null ? sys : sys + HAS_ACTIVE_TASK.formatted(activeTaskName);
    }
}

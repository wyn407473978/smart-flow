package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteSpec;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * B3：自由对话，同时是根路由的兜底节点。流式输出。
 * <p>
 * 在任务进行中用户插话闲聊时也会走到这里；"是否继续刚才的任务"的提示由引擎在轮末统一追加，
 * 这个节点只管把当前问题答好。
 */
@Component
public class ChatNode extends AbstractNode {

    private static final String SYSTEM_PROMPT = """
            你是"智慧民生"服务助手，语气亲切、简洁、专业，用中文回答。
            你目前能帮用户办理的事情：%s，以及日常交流。
            用户问到上面没有列出的能力（例如需要查询个人业务数据但当前不支持）时，如实说明暂不支持，不要编造结果。
            """;

    private static final String HAS_ACTIVE_TASK = """
            用户当前有一个进行中的"%s"，这次只是临时问了别的问题。
            请只回答当前问题，不要替用户填写或猜测这个任务的任何参数。
            也不要提醒或询问用户是否继续这个任务，系统会在你回答之后统一提醒，你再提一遍会重复。
            """;

    private final RouteCatalog catalog;

    public ChatNode(NodeSupport support, RouteCatalog catalog) {
        super(support);
        this.catalog = catalog;
    }

    @Override
    public String id() {
        return FlowNames.CHAT;
    }

    @Override
    public NodeType type() {
        return NodeType.CHAT;
    }

    @Override
    public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.fallback(FlowNames.ROUTER, "CHAT", "闲聊",
                "问候、闲聊、常识问答，以及其他模块都处理不了的问题",
                List.of("你好", "你是谁", "讲个笑话"),
                1000));
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("reply · system", systemPrompt("", null));
        prompts.put("reply · system（有记忆、任务进行中插话时）",
                systemPrompt("\n{记忆块：长期记忆 / 早期对话摘要，没有则不出现}\n", "{任务名}"));
        return prompts;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        String sys = systemPrompt(memoryBlock(state),
                state.activeTask().map(t -> catalog.taskName(t.getTaskType())).orElse(null));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system(sys));
        messages.addAll(historyMessages(state));
        messages.add(UserMessage.from(state.userInput()));

        String text = streamReply(scope, "reply", messages);
        return reply(text);
    }

    /**
     * @param memory         记忆块（长期记忆、早期对话摘要），可以为空
     * @param activeTaskName 进行中任务的名字，没有任务时为 null
     */
    private String systemPrompt(String memory, String activeTaskName) {
        // 能力清单也来自子节点声明，新增节点后闲聊节点自动"知道"
        String abilities = catalog.options(FlowNames.ROUTER).stream()
                .filter(o -> !o.spec().fallback())
                .map(o -> o.spec().name())
                .collect(Collectors.joining("、"));
        StringBuilder sys = new StringBuilder(SYSTEM_PROMPT.formatted(abilities.isEmpty() ? "（暂无）" : abilities));
        if (memory != null && !memory.isBlank()) {
            sys.append(memory);
        }
        if (activeTaskName != null) {
            sys.append('\n').append(HAS_ACTIVE_TASK.formatted(activeTaskName));
        }
        return sys.toString();
    }
}

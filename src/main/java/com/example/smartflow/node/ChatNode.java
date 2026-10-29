package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.RouteDecision;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B3：自由对话。流式输出。
 * <p>
 * 在活动创建过程中用户插话闲聊时也会走到这里；"是否继续刚才的任务"的提示由引擎在轮末统一追加，
 * 这个节点只管把当前问题答好。
 */
@Component
public class ChatNode extends AbstractNode {

    private static final String SYSTEM_PROMPT = """
            你是"智慧民生"服务助手，语气亲切、简洁、专业，用中文回答。
            你可以帮助用户：创建社区/民生活动、查询民生业务信息、日常交流。
            不确定的事实不要编造；涉及具体个人数据时说明需要通过业务查询获取。
            """;

    private static final String BIZ_QUERY_NOT_READY = """
            用户这次想查询业务信息，但业务查询能力还在接入中。
            请礼貌地告诉用户这项能力暂未开放，可以先帮他做其他事情。不要编造任何查询结果。
            """;

    private static final String HAS_ACTIVE_TASK = """
            用户当前有一个进行中的活动创建任务，这次只是临时问了别的问题。
            请只回答当前问题，不要替用户填写或猜测活动的任何参数。
            """;

    public ChatNode(NodeSupport support) {
        super(support);
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
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        StringBuilder sys = new StringBuilder(SYSTEM_PROMPT);
        if (!state.summary().isBlank()) {
            sys.append("\n早期对话摘要：").append(state.summary()).append('\n');
        }
        if (state.route().map(r -> r.intent() == RouteDecision.Intent.BIZ_QUERY).orElse(false)) {
            sys.append('\n').append(BIZ_QUERY_NOT_READY);
        }
        if (state.activeTask().isPresent()) {
            sys.append('\n').append(HAS_ACTIVE_TASK);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system(sys.toString()));
        messages.addAll(historyMessages(state, support.props().memory().recentMessages()));
        messages.add(UserMessage.from(state.userInput()));

        String text = streamReply(scope, "reply", messages);
        return reply(text);
    }
}

package com.example.smartflow.node;

import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.RouteDecision.Action;
import com.example.smartflow.flow.RouteDecision.Intent;
import com.example.smartflow.flow.TaskFrame;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A 节点：全局路由，每轮必经。
 * <p>
 * 模型只负责判断 action + intent，"去哪个节点、任务栈怎么变"全部由代码决定，
 * 这样路由规则可测试、可解释，模型换了也不影响流程正确性。
 */
@Component
public class RouterNode extends AbstractNode {

    private static final String SYSTEM_PROMPT = """
            你是"智慧民生"助手的路由模块。你只负责判断用户本轮消息应该交给哪个模块处理，不负责回答用户。

            意图 intent（三选一）：
            - ACTIVITY_CREATE：用户想创建、发起、策划一个活动，或者正在回答活动创建流程中提出的问题（例如目标人群的年龄、性别、区域）
            - BIZ_QUERY：查询业务信息，例如社保、医保、公积金、缴费记录、办件进度、政策条件等
            - CHAT：问候、闲聊、与以上都无关的问题

            动作 action（四选一）：
            - CONTINUE：当前有进行中的任务，用户在回答、补充或修改这个任务的信息
            - START：开始一个新任务（当前没有进行中的任务，或者用户明确要开始另一个任务）
            - DIGRESS：当前有进行中的任务，但用户临时问了别的事情（业务查询或闲聊），并没有放弃当前任务
            - CANCEL：用户明确表示不想继续、取消、算了、不创建了

            判断要点：
            1. 如果当前任务正在等待某些信息，而用户这句话恰好提供了这些信息（哪怕很简短，比如"女性"、"60岁以上"），判定为 CONTINUE。
            2. 当前没有进行中的任务时，action 只能是 START。
            3. 只有用户明确表达放弃时才判定为 CANCEL，单纯问别的事情是 DIGRESS。

            只输出 JSON，不要输出任何其他内容，格式：
            {"action": "CONTINUE|START|DIGRESS|CANCEL", "intent": "ACTIVITY_CREATE|BIZ_QUERY|CHAT", "reason": "一句话理由"}
            """;

    private static final String CANCEL_REPLY = "好的，已为你取消当前的活动创建。还有其他需要可以随时告诉我。";

    /** 模型的原始输出 */
    public record RouterOutput(Action action, Intent intent, String reason) {
    }

    public RouterNode(NodeSupport support) {
        super(support);
    }

    @Override
    public String id() {
        return FlowNames.ROUTER;
    }

    @Override
    public NodeType type() {
        return NodeType.ROUTER;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) {
        // 只保留进行中的任务，已完成 / 已取消的出栈
        List<TaskFrame> stack = new ArrayList<>();
        for (TaskFrame f : state.taskStack()) {
            if (f.isOpen()) {
                stack.add(f.copy());
            }
        }
        Optional<TaskFrame> active = stack.isEmpty() ? Optional.empty() : Optional.of(stack.get(stack.size() - 1));

        RouterOutput out = classify(state, scope, active);
        Map<String, Object> updates = updates();
        RouteDecision decision = resolve(out, active, stack, updates, scope);

        updates.put(FlowState.TASK_STACK, stack);
        updates.put(FlowState.ROUTE, decision);
        scope.turn().route(decision);
        scope.turn().emit(TurnEvent.of("route",
                "action", decision.action().name(),
                "intent", decision.intent().name(),
                "target", decision.target(),
                "reason", decision.reason(),
                "fallback", decision.fallback()));
        return updates;
    }

    // ------------------------------------------------------------------ 模型判断

    private RouterOutput classify(FlowState state, NodeScope scope, Optional<TaskFrame> active) {
        String taskDesc = active
                .map(t -> "任务类型：%s；当前阶段：%s；已收集信息：%s".formatted(
                        t.getTaskType(), t.getCurrentNode(), t.getSlots()))
                .orElse("（无）");

        String userContent = """
                当前进行中的任务：%s

                最近对话：
                %s
                用户本轮消息：%s
                """.formatted(taskDesc, historyAsText(state, 6), state.userInput());

        List<ChatMessage> messages = List.of(system(SYSTEM_PROMPT), UserMessage.from(userContent));
        try {
            RouterOutput out = chatJson(scope, "route", support.models().router(), messages, RouterOutput.class);
            return normalize(out, active.isPresent());
        } catch (LlmJsonException e) {
            log.warn("路由输出解析失败，走兜底", e);
            return null;
        }
    }

    private static RouterOutput normalize(RouterOutput out, boolean hasActive) {
        Action action = out.action();
        Intent intent = out.intent() == null ? Intent.CHAT : out.intent();
        if (action == null) {
            action = hasActive ? Action.CONTINUE : Action.START;
        }
        if (!hasActive && action != Action.START) {
            // 没有进行中的任务时，CONTINUE / DIGRESS / CANCEL 都没有意义
            action = Action.START;
        }
        return new RouterOutput(action, intent, out.reason());
    }

    // ------------------------------------------------------------------ 代码决定去向

    private RouteDecision resolve(RouterOutput out, Optional<TaskFrame> active, List<TaskFrame> stack,
                                  Map<String, Object> updates, NodeScope scope) {
        if (out == null) {
            // 兜底：有任务就继续任务，否则闲聊
            return active
                    .map(t -> new RouteDecision(Action.CONTINUE, Intent.ACTIVITY_CREATE, t.getCurrentNode(), "fallback", true))
                    .orElse(new RouteDecision(Action.START, Intent.CHAT, FlowNames.CHAT, "fallback", true));
        }

        return switch (out.action()) {
            case CONTINUE -> new RouteDecision(Action.CONTINUE, out.intent(),
                    active.orElseThrow().getCurrentNode(), out.reason(), false);

            case CANCEL -> {
                active.orElseThrow().setStatus(TaskFrame.TaskStatus.CANCELLED);
                stack.removeIf(f -> !f.isOpen());
                updates.put(FlowState.REPLIES, emitText(scope, CANCEL_REPLY));
                yield new RouteDecision(Action.CANCEL, out.intent(), FlowNames.END_TURN, out.reason(), false);
            }

            case DIGRESS -> {
                // 模型说"插话"，但意图其实就是当前任务本身，按继续处理
                if (out.intent() == Intent.ACTIVITY_CREATE && isActivityTask(active)) {
                    yield new RouteDecision(Action.CONTINUE, out.intent(),
                            active.orElseThrow().getCurrentNode(), out.reason(), false);
                }
                yield new RouteDecision(Action.DIGRESS, out.intent(), targetFor(out.intent()), out.reason(), false);
            }

            case START -> {
                if (out.intent() == Intent.ACTIVITY_CREATE) {
                    if (isActivityTask(active)) {
                        // 已经在创建活动了，不重复开任务
                        yield new RouteDecision(Action.CONTINUE, out.intent(),
                                active.orElseThrow().getCurrentNode(), out.reason(), false);
                    }
                    stack.add(TaskFrame.start(FlowNames.TASK_ACTIVITY_CREATE, FlowNames.COLLECT_TARGET));
                    yield new RouteDecision(Action.START, out.intent(), FlowNames.COLLECT_TARGET, out.reason(), false);
                }
                yield new RouteDecision(Action.START, out.intent(), targetFor(out.intent()), out.reason(), false);
            }
        };
    }

    private static boolean isActivityTask(Optional<TaskFrame> active) {
        return active.map(t -> FlowNames.TASK_ACTIVITY_CREATE.equals(t.getTaskType())).orElse(false);
    }

    /** 非任务类意图对应的节点 */
    private static String targetFor(Intent intent) {
        return switch (intent) {
            // B2 业务查询节点接入后改成 FlowNames.BIZ_QUERY
            case BIZ_QUERY, CHAT -> FlowNames.CHAT;
            case ACTIVITY_CREATE -> FlowNames.COLLECT_TARGET;
        };
    }
}

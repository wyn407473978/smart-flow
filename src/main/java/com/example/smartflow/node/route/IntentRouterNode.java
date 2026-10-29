package com.example.smartflow.node.route;

import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.RouteDecision.Action;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.route.RouteCatalog.RouteOption;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 通用意图路由节点。
 * <p>
 * 提示词由固定模板 + 子节点的 {@link RouteSpec} 拼成：模板只描述"怎么判断动作"，
 * "有哪些去向、各自什么时候去"全部来自子节点自己的声明。新增子节点时这个类和它的提示词都不用改。
 * <p>
 * 去向和任务栈的变化由代码决定，模型只输出 action + intent。
 * <p>
 * 根路由 A 是它的一个子类；以后如果某个分支下的子节点多了需要二级路由（例如 B1 下面有多种任务），
 * 再写一个子类、换个 id，子节点把 parent 指向它即可。
 */
public abstract class IntentRouterNode extends AbstractNode {

    private static final String PROMPT_TEMPLATE = """
            你是"%s"的路由模块。你只负责判断用户本轮消息应该交给哪个模块处理，不负责回答用户。

            可选模块（intent）：
            %s

            动作（action，四选一）：
            - CONTINUE：当前有进行中的任务，用户在回答、补充或修改这个任务的信息
            - START：开始一个新任务或新话题（当前没有进行中的任务时只能是 START）
            - DIGRESS：当前有进行中的任务，但用户临时问了别的事情，并没有放弃当前任务
            - CANCEL：用户明确表示不想继续当前任务（取消、算了、不弄了）

            判断要点：
            1. 当前任务正在等待某些信息，而用户这句话恰好提供了这些信息（哪怕很简短，比如一个词），判定为 CONTINUE，intent 填当前任务对应的模块。
            2. 只有用户明确表达放弃时才判定为 CANCEL，单纯问别的事情是 DIGRESS。
            3. 拿不准属于哪个模块时，intent 选 %s。

            只输出 JSON，不要输出任何其他内容，格式：
            {"action": "CONTINUE|START|DIGRESS|CANCEL", "intent": "上面列出的模块编码之一", "reason": "一句话理由"}
            """;

    /** 模型的原始输出 */
    public record RouterOutput(Action action, String intent, String reason) {
    }

    protected final RouteCatalog catalog;

    protected IntentRouterNode(NodeSupport support, RouteCatalog catalog) {
        super(support);
        this.catalog = catalog;
    }

    /** 助手的名字，出现在提示词第一句 */
    protected abstract String assistantName();

    @Override
    public NodeType type() {
        return NodeType.ROUTER;
    }

    /** 当前实际生效的路由提示词，方便调试和写测试 */
    public String systemPrompt() {
        List<RouteOption> options = catalog.options(id());
        String intents = options.stream().map(IntentRouterNode::describe).collect(Collectors.joining("\n"));
        return PROMPT_TEMPLATE.formatted(assistantName(), intents, catalog.fallback(id()).spec().intent());
    }

    private static String describe(RouteOption o) {
        RouteSpec s = o.spec();
        StringBuilder sb = new StringBuilder("- ").append(s.intent())
                .append("（").append(s.name()).append(s.isTaskEntry() ? "，多轮任务" : "").append("）：")
                .append(s.description());
        if (!s.examples().isEmpty()) {
            sb.append("\n  例如：").append(s.examples().stream().map(e -> "「" + e + "」").collect(Collectors.joining(" ")));
        }
        return sb.toString();
    }

    // =====================================================================

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
        goTo(updates, decision.target());
        scope.turn().route(decision);
        scope.turn().emit(TurnEvent.of("route",
                "action", decision.action().name(),
                "intent", decision.intent(),
                "target", decision.target(),
                "reason", decision.reason(),
                "fallback", decision.fallback()));
        return updates;
    }

    // ------------------------------------------------------------------ 模型判断

    private RouterOutput classify(FlowState state, NodeScope scope, Optional<TaskFrame> active) {
        String taskDesc = active.map(t -> {
            String stage = catalog.stageDescription(t.getCurrentNode());
            return "%s（%s）%n当前阶段：%s%n已收集信息：%s".formatted(
                    catalog.taskName(t.getTaskType()), t.getTaskType(),
                    stage.isBlank() ? t.getCurrentNode() : stage, t.getSlots());
        }).orElse("（无）");

        String userContent = """
                当前进行中的任务：%s

                最近对话：
                %s
                用户本轮消息：%s
                """.formatted(taskDesc, historyAsText(state, 6), state.userInput());

        List<ChatMessage> messages = List.of(system(systemPrompt()), UserMessage.from(userContent));
        try {
            return chatJson(scope, "route", support.models().router(), messages, RouterOutput.class);
        } catch (LlmJsonException e) {
            log.warn("路由输出解析失败，走兜底", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 代码决定去向

    private RouteDecision resolve(RouterOutput out, Optional<TaskFrame> active, List<TaskFrame> stack,
                                  Map<String, Object> updates, NodeScope scope) {
        RouteOption fallback = catalog.fallback(id());

        // 模型输出完全不可用：有任务就继续任务，否则去兜底节点
        if (out == null) {
            return active.filter(t -> catalog.exists(t.getCurrentNode()))
                    .map(t -> new RouteDecision(Action.CONTINUE, t.getTaskType(), t.getCurrentNode(), "fallback", true))
                    .orElse(new RouteDecision(Action.START, fallback.spec().intent(), fallback.nodeId(), "fallback", true));
        }

        Optional<RouteOption> matched = catalog.find(id(), out.intent());
        RouteOption option = matched.orElse(fallback);
        boolean usedFallback = matched.isEmpty();
        Action action = out.action();
        if (action == null) {
            action = active.isPresent() ? Action.CONTINUE : Action.START;
        }
        if (active.isEmpty()) {
            // 没有进行中的任务时，CONTINUE / DIGRESS / CANCEL 都没有意义
            action = Action.START;
        }

        switch (action) {
            case CONTINUE -> {
                TaskFrame task = active.orElseThrow();
                if (catalog.exists(task.getCurrentNode())) {
                    return new RouteDecision(Action.CONTINUE, task.getTaskType(), task.getCurrentNode(), out.reason(), usedFallback);
                }
                // 任务停留的节点已经下线（例如发版删了节点），放弃这个任务
                log.warn("任务 {} 停留的节点 {} 不存在，任务取消", task.getTaskType(), task.getCurrentNode());
                task.setStatus(TaskFrame.TaskStatus.CANCELLED);
                stack.removeIf(f -> !f.isOpen());
                return new RouteDecision(Action.START, fallback.spec().intent(), fallback.nodeId(), out.reason(), true);
            }
            case CANCEL -> {
                TaskFrame task = active.orElseThrow();
                task.setStatus(TaskFrame.TaskStatus.CANCELLED);
                stack.removeIf(f -> !f.isOpen());
                updates.put(FlowState.REPLIES, emitText(scope, cancelReply(task)));
                return new RouteDecision(Action.CANCEL, task.getTaskType(), FlowNames.END_TURN, out.reason(), usedFallback);
            }
            default -> {
                // START / DIGRESS 统一按"去哪个节点"处理
                if (option.spec().isTaskEntry()) {
                    if (active.map(t -> t.getTaskType().equals(option.spec().taskType())).orElse(false)) {
                        // 就是当前任务本身，按继续处理，不重复开任务
                        TaskFrame task = active.get();
                        return new RouteDecision(Action.CONTINUE, task.getTaskType(), task.getCurrentNode(), out.reason(), usedFallback);
                    }
                    // 新任务压栈；如果原来有任务，它留在栈里，新任务完成或取消后再回到它
                    stack.add(TaskFrame.start(option.spec().taskType(), option.nodeId()));
                    return new RouteDecision(Action.START, option.spec().intent(), option.nodeId(), out.reason(), usedFallback);
                }
                // 一次性节点：有任务时算插话（回答完提示继续任务），没有任务时算开始
                Action a = active.isPresent() ? Action.DIGRESS : Action.START;
                return new RouteDecision(a, option.spec().intent(), option.nodeId(), out.reason(), usedFallback);
            }
        }
    }

    protected String cancelReply(TaskFrame task) {
        return "好的，已为你取消当前的" + catalog.taskName(task.getTaskType()) + "。还有其他需要可以随时告诉我。";
    }
}

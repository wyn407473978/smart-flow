package com.example.smartflow.node.route;

import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.RouteDecision.Action;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.HistoryPolicy;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.route.RouteCatalog.RouteOption;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            - CONTINUE：用户在回答"待回答的问题"，或者在回答、补充、修改"进行中的任务"的信息
            - START：开始一个新任务或新话题（既没有待回答的问题、也没有进行中的任务时，只能是 START）
            - DIGRESS：有进行中的任务，但用户临时问了别的事情，并没有放弃这个任务
            - CANCEL：用户明确表示不想继续了（取消、算了、不弄了、不用了）

            判断要点：
            1. 有"待回答的问题"时，先判断用户是不是在回答它：简短的肯定或否定（"是的""对""好""不是"），
               或者正好提供了它要的信息，通常就是在回答它。此时 action 填 CONTINUE，intent 填提出这个问题的模块。
            2. 进行中的任务正在等待某些信息，用户这句话恰好提供了这些信息（哪怕只有一个词），
               action 填 CONTINUE，intent 填这个任务对应的模块。
            3. 只有用户明确表达放弃时才判定为 CANCEL，单纯问别的事情是 DIGRESS。
            4. 拿不准属于哪个模块时，intent 选 %s。

            只输出 JSON，不要输出任何其他内容，格式：
            {"action": "CONTINUE|START|DIGRESS|CANCEL", "intent": "上面列出的模块编码之一", "reason": "一句话理由"}
            """;

    private static final String USER_TEMPLATE = """
            待回答的问题：%s

            进行中的任务：%s
            %s

            最近对话：
            %s
            用户本轮消息：%s
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

    /** 路由每轮都要调用，只带最近 6 条和早期摘要，不带长期记忆和工具结果，保持轻量 */
    @Override
    public HistoryPolicy historyPolicy() {
        return super.historyPolicy().withMaxMessages(6).withUserMemory(false).withToolResults(false);
    }

    /** 当前实际生效的路由提示词，方便调试和写测试 */
    public String systemPrompt() {
        List<RouteOption> options = catalog.options(id());
        String intents = options.stream().map(IntentRouterNode::describe).collect(Collectors.joining("\n"));
        return PROMPT_TEMPLATE.formatted(assistantName(), intents, catalog.fallback(id()).spec().intent());
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("route · system", systemPrompt());
        prompts.put("route · user", USER_TEMPLATE.formatted(
                "来自\"{模块名}\"（{意图编码}）：{追问原话}　　← 上一轮没有节点追问时是：（无）",
                "{任务名（任务类型）}\n当前阶段：{阶段说明}\n已收集信息：{槽位}　　← 没有进行中的任务时这三行是：（无）",
                "\n{早期对话摘要，没有则不出现}\n",
                "{最近 6 条对话}", "{用户本轮消息}"));
        return prompts;
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
        // 只保留进行中的帧，已完成 / 已取消的出栈
        List<TaskFrame> stack = new ArrayList<>();
        for (TaskFrame f : state.taskStack()) {
            if (f.isOpen()) {
                stack.add(f.copy());
            }
        }
        // pending：上一轮某个一次性节点追问了用户，正在等回答（只可能在栈顶）
        Optional<TaskFrame> pending = Optional.empty();
        if (!stack.isEmpty() && stack.get(stack.size() - 1).isEphemeral()) {
            pending = Optional.of(stack.get(stack.size() - 1));
        }
        // task：进行中的多轮任务
        Optional<TaskFrame> task = Optional.empty();
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (!stack.get(i).isEphemeral()) {
                task = Optional.of(stack.get(i));
                break;
            }
        }

        RouterOutput out = classify(state, scope, pending, task);
        Map<String, Object> updates = updates();
        RouteDecision decision = resolve(out, pending, task, stack, updates, scope);

        updates.put(FlowState.TASK_STACK, stack);
        updates.put(FlowState.ROUTE, decision);
        goTo(updates, decision.target());
        scope.turn().route(decision);
        scope.turn().emit(TurnEvent.of("route",
                "action", decision.action().name(),
                "intent", decision.intent(),
                "target", decision.target(),
                "reason", decision.reason(),
                "fallback", decision.fallback(),
                "sideTrip", decision.sideTrip()));
        return updates;
    }

    // ------------------------------------------------------------------ 模型判断

    private RouterOutput classify(FlowState state, NodeScope scope, Optional<TaskFrame> pending, Optional<TaskFrame> task) {
        String pendingDesc = pending.map(p -> "来自\"%s\"（%s）：%s".formatted(
                catalog.taskName(p.getTaskType()), p.getTaskType(), p.getAwaiting())).orElse("（无）");
        String taskDesc = task.map(t -> {
            String stage = catalog.stageDescription(t.getCurrentNode());
            return "%s（%s）%n当前阶段：%s%n已收集信息：%s".formatted(
                    catalog.taskName(t.getTaskType()), t.getTaskType(),
                    stage.isBlank() ? t.getCurrentNode() : stage, t.getSlots());
        }).orElse("（无）");

        String userContent = USER_TEMPLATE.formatted(pendingDesc, taskDesc, memoryBlock(state),
                historyAsText(state, historyPolicy().maxMessages()), state.userInput());

        List<ChatMessage> messages = List.of(system(systemPrompt()), UserMessage.from(userContent));
        try {
            return chatJson(scope, "route", support.models().router(), messages, RouterOutput.class);
        } catch (LlmJsonException e) {
            log.warn("路由输出解析失败，走兜底", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 代码决定去向

    private RouteDecision resolve(RouterOutput out, Optional<TaskFrame> pending, Optional<TaskFrame> task,
                                  List<TaskFrame> stack, Map<String, Object> updates, NodeScope scope) {
        RouteOption fallback = catalog.fallback(id());
        boolean hasTask = task.isPresent();

        // 模型输出完全不可用：有追问就回到追问的节点，否则有任务继续任务，否则去兜底节点
        if (out == null) {
            if (pending.filter(p -> catalog.exists(p.getCurrentNode())).isPresent()) {
                TaskFrame p = pending.get();
                return new RouteDecision(Action.CONTINUE, p.getTaskType(), p.getCurrentNode(), "fallback", true, hasTask);
            }
            dropPending(stack, pending);
            return task.filter(t -> catalog.exists(t.getCurrentNode()))
                    .map(t -> new RouteDecision(Action.CONTINUE, t.getTaskType(), t.getCurrentNode(), "fallback", true, false))
                    .orElse(new RouteDecision(Action.START, fallback.spec().intent(), fallback.nodeId(), "fallback", true, hasTask));
        }

        Optional<RouteOption> matched = catalog.find(id(), out.intent());
        RouteOption option = matched.orElse(fallback);
        boolean usedFallback = matched.isEmpty();
        Action action = out.action();
        if (action == null) {
            action = pending.isPresent() || hasTask ? Action.CONTINUE : Action.START;
        }
        if (pending.isEmpty() && !hasTask) {
            // 什么都没有在等，CONTINUE / DIGRESS / CANCEL 都没有意义
            action = Action.START;
        }

        switch (action) {
            case CONTINUE -> {
                // "继续"的是追问还是任务，用模型给的 intent 区分：intent 是任务本身就继续任务，否则视为回答追问
                boolean answersPending = pending.isPresent()
                        && (!hasTask || !task.get().getTaskType().equalsIgnoreCase(String.valueOf(out.intent())));
                if (answersPending && catalog.exists(pending.get().getCurrentNode())) {
                    TaskFrame p = pending.get();
                    // 追问帧留在栈里，由节点自己决定结束还是继续追问
                    return new RouteDecision(Action.CONTINUE, p.getTaskType(), p.getCurrentNode(), out.reason(), usedFallback, hasTask);
                }
                dropPending(stack, pending);
                if (!hasTask) {
                    return startOrDigress(option, task, stack, out, usedFallback);
                }
                TaskFrame t = task.get();
                if (catalog.exists(t.getCurrentNode())) {
                    return new RouteDecision(Action.CONTINUE, t.getTaskType(), t.getCurrentNode(), out.reason(), usedFallback, false);
                }
                // 任务停留的节点已经下线（例如发版删了节点），放弃这个任务
                log.warn("任务 {} 停留的节点 {} 不存在，任务取消", t.getTaskType(), t.getCurrentNode());
                t.setStatus(TaskFrame.TaskStatus.CANCELLED);
                stack.removeIf(f -> !f.isOpen());
                return new RouteDecision(Action.START, fallback.spec().intent(), fallback.nodeId(), out.reason(), true, false);
            }
            case CANCEL -> {
                dropPending(stack, pending);
                if (hasTask) {
                    TaskFrame t = task.get();
                    t.setStatus(TaskFrame.TaskStatus.CANCELLED);
                    stack.removeIf(f -> !f.isOpen());
                    updates.put(FlowState.REPLIES, emitText(scope, cancelReply(t)));
                    return new RouteDecision(Action.CANCEL, t.getTaskType(), FlowNames.END_TURN, out.reason(), usedFallback, false);
                }
                // 只有一个追问：用户说不用了，那就不问了
                updates.put(FlowState.REPLIES, emitText(scope, "好的，那先不查了。还有其他需要可以随时告诉我。"));
                return new RouteDecision(Action.CANCEL, pending.map(TaskFrame::getTaskType).orElse(out.intent()),
                        FlowNames.END_TURN, out.reason(), usedFallback, false);
            }
            default -> {
                // START / DIGRESS：用户没有回答追问，追问作废
                dropPending(stack, pending);
                return startOrDigress(option, task, stack, out, usedFallback);
            }
        }
    }

    /** 去一个新的地方：多轮任务入口就开任务（或继续同类任务），一次性节点就是开始或插话 */
    private RouteDecision startOrDigress(RouteOption option, Optional<TaskFrame> task, List<TaskFrame> stack,
                                         RouterOutput out, boolean usedFallback) {
        if (option.spec().isTaskEntry()) {
            if (task.map(t -> t.getTaskType().equals(option.spec().taskType())).orElse(false)) {
                // 就是当前任务本身，按继续处理，不重复开任务
                TaskFrame t = task.get();
                return new RouteDecision(Action.CONTINUE, t.getTaskType(), t.getCurrentNode(), out.reason(), usedFallback, false);
            }
            // 新任务压栈；如果原来有任务，它留在栈里，新任务完成或取消后再回到它
            stack.add(TaskFrame.start(option.spec().taskType(), option.nodeId()));
            return new RouteDecision(Action.START, option.spec().intent(), option.nodeId(), out.reason(), usedFallback, false);
        }
        // 一次性节点：有任务时算插话（处理完提示继续任务），没有任务时算开始
        Action a = task.isPresent() ? Action.DIGRESS : Action.START;
        return new RouteDecision(a, option.spec().intent(), option.nodeId(), out.reason(), usedFallback, task.isPresent());
    }

    private static void dropPending(List<TaskFrame> stack, Optional<TaskFrame> pending) {
        pending.ifPresent(p -> p.setStatus(TaskFrame.TaskStatus.DONE));
        stack.removeIf(f -> !f.isOpen());
    }

    protected String cancelReply(TaskFrame task) {
        return "好的，已为你取消当前的" + catalog.taskName(task.getTaskType()) + "。还有其他需要可以随时告诉我。";
    }
}

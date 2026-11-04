package com.example.smartflow.node;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.llm.NodeModelClient;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.node.slot.AskMode;
import com.example.smartflow.node.slot.FollowUp;
import com.example.smartflow.tool.FlowTool;
import com.example.smartflow.tool.ToolDescriptions;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 所有工作流节点的基类。
 * <p>
 * 统一负责：
 * <ul>
 *   <li>节点执行记录（flow_node_exec：输入状态、输出更新、耗时、异常）</li>
 *   <li>节点开始 / 结束事件推送</li>
 *   <li>状态更新与追问等待状态管理</li>
 *   <li>提供模型调用、工具循环和上下文组装的便捷入口，具体实现委托给 NodeSupport 中的组件</li>
 * </ul>
 * 子类只实现 {@link #execute(FlowState, NodeScope)}。
 */
public abstract class AbstractNode implements NodeAction<FlowState> {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final NodeSupport support;

    protected AbstractNode(NodeSupport support) {
        this.support = support;
    }

    /** 节点 ID，同时是图里的节点名 */
    public abstract String id();

    public abstract NodeType type();

    /**
     * 声明自己挂在哪个路由节点下、在什么情况下应该被路由到。
     * 返回空表示不能被路由直接到达（例如任务的第二、第三阶段，只能由上一阶段推进过来）。
     * <p>
     * 父路由节点的提示词由所有子节点的声明拼成，所以新增子节点时父节点不用改。
     */
    public Optional<RouteSpec> routeSpec() {
        return Optional.empty();
    }

    /**
     * 任务停留在本节点时，告诉路由模型"正在等用户提供什么"，
     * 帮助它判断用户的简短回答（例如"女性"）是在继续任务。只有任务阶段节点需要覆盖。
     */
    public String stageDescription() {
        return "";
    }

    /**
     * 本节点用到的提示词，给调试页面展示。key 是用途（和 flow_llm_call.purpose 对应），value 是提示词。
     * 依赖注册信息的部分要按当前注册情况实时生成；只有运行时才知道的内容（用户输入、已收集信息等）用 {占位符} 表示。
     * 不调用模型的节点返回空即可。
     */
    public Map<String, String> promptPreview() {
        return Map.of();
    }

    /**
     * 节点业务逻辑。
     *
     * @return 对状态的更新（只放需要改变的 key）
     */
    protected abstract Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception;

    // =====================================================================
    // 模板方法：记录 + 事件
    // =====================================================================

    @Override
    public final Map<String, Object> apply(FlowState state) throws Exception {
        TurnContext turn = support.turnsRepository().get(state.turnId());
        int seq = turn.nextNodeSeq(id());
        String execId = UUID.randomUUID().toString();
        long start = System.nanoTime();

        support.traceRepository().insertNodeExec(execId, turn.turnId(), turn.sessionId(), seq, id(), type().name(), inputSnapshot(state));
        turn.emit(TurnEvent.of("node_start", "node", id(), "type", type().name(), "seq", seq));

        try {
            Map<String, Object> result = execute(state, new NodeScope(turn, execId, id()));
            Map<String, Object> updates = result == null ? new HashMap<>() : new HashMap<>(result);
            // 没有指定下一个节点就结束本轮；必须每次都写，否则会沿用上一个节点留下的值
            updates.putIfAbsent(FlowState.NEXT_NODE, FlowNames.END_TURN);
            long ms = elapsedMs(start);
            support.traceRepository().finishNodeExec(execId, updates, "SUCCESS", null, ms);
            turn.emit(TurnEvent.of("node_end", "node", id(), "status", "SUCCESS", "latencyMs", ms));
            return updates;
        } catch (Exception e) {
            long ms = elapsedMs(start);
            log.error("节点执行失败 node={} turn={}", id(), turn.turnId(), e);
            support.traceRepository().finishNodeExec(execId, null, "ERROR",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), ms);
            turn.emit(TurnEvent.of("node_end", "node", id(), "status", "ERROR", "latencyMs", ms));
            throw e;
        }
    }

    /**
     * 记录到 flow_node_exec.input_state 的内容。历史消息只记条数，完整内容在 flow_llm_call 里能看到。
     * 子类可以覆盖。
     */
    protected Map<String, Object> inputSnapshot(FlowState state) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("userInput", state.userInput());
        snap.put("historySize", state.history().size());
        snap.put("taskStack", state.taskStack());
        state.route().ifPresent(r -> snap.put("route", r));
        snap.put("replies", state.replies());
        return snap;
    }

    // =====================================================================
    // 模型调用
    // =====================================================================

    /** 带 trace 上下文的调用选项，listener 用它把这次调用关联到当前节点 */
    protected ChatRequestOptions traceOptions(NodeScope scope, String purpose, boolean streaming) {
        return traceOptions(scope, purpose, streaming, null);
    }

    /** @param callId 预先指定的模型调用 ID，为 null 时由 listener 生成 */
    protected ChatRequestOptions traceOptions(NodeScope scope, String purpose, boolean streaming, String callId) {
        return support.modelClient().traceOptions(scope, purpose, streaming, callId);
    }

    /** 普通（非流式）调用 */
    protected ChatResponse chat(NodeScope scope, String purpose, ChatModel model, ChatRequest request) {
        return support.modelClient().chat(model, request, traceOptions(scope, purpose, false));
    }

    /**
     * 要求模型输出 JSON 并解析成对象。解析失败会把错误输出和纠正指令追加进对话再试一次，
     * 两次都失败抛 {@link LlmJsonException}，由节点决定兜底策略。
     * <p>
     * 用的是 JSON mode（不带 schema），兼容性最好；字段约定写在提示词里。
     * 注意：百炼等部分平台要求开启 JSON mode 时提示词里必须出现 "JSON" 字样。
     */
    protected <T> T chatJson(NodeScope scope, String purpose, ChatModel model,
                             List<ChatMessage> messages, Class<T> type) {
        try {
            return support.modelClient().chatJson(scope, purpose, messages, type,
                    (callPurpose, request) -> chat(scope, callPurpose, model, request));
        } catch (com.example.smartflow.llm.LlmJsonException e) {
            // 保留节点原有的异常契约，已有子类仍可捕获 AbstractNode.LlmJsonException。
            throw new LlmJsonException(purpose, e.lastOutput(), e.getCause());
        }
    }

    /**
     * 流式生成回复：token 实时推给前端，返回完整文本。调用方负责把返回值写进状态（{@link #reply(String)}）。
     */
    protected String streamReply(NodeScope scope, String purpose, List<ChatMessage> messages) throws Exception {
        return support.modelClient().streamReply(scope, messages, traceOptions(scope, purpose, true, null));
    }

    /** 收集信息时的追问方式。默认取配置 flow.slot-filling.ask-mode，个别节点需要不同方式时覆盖 */
    protected AskMode askMode() {
        return support.props().slotFilling().askMode();
    }

    /**
     * 按代码给出的追问计划追问，接在 text 已有内容（通常是代码先输出的"已记录……"和校验提示）后面。
     * {@link AskMode#MODEL} 下由追问模型措辞并流式输出；{@link AskMode#CODE}、计划里没有要问的项，
     * 或模型超时、出错、没有输出时，改用 codeQuestion。返回整段回复。
     */
    protected String askFollowUp(FlowState state, NodeScope scope, FollowUp.Plan plan, StringBuilder text,
                                 String codeQuestion) {
        if (askMode() == AskMode.MODEL && !plan.asking().isEmpty()) {
            FlowProperties.SlotFilling cfg = support.props().slotFilling();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            system(FollowUp.systemPrompt(support.assistant(), plan)),
                            UserMessage.from(FollowUp.userPrompt(lastAssistantMessage(state), state.userInput()))))
                    .maxOutputTokens(cfg.maxOutputTokens())
                    .build();
            NodeModelClient.GuardedStream outcome = streamGuarded(scope, "ask_missing", support.models().ask(),
                    request, text, Duration.ofMillis(cfg.firstTokenTimeoutMs()));
            if (outcome == NodeModelClient.GuardedStream.COMPLETED) {
                return text.toString();
            }
            log.warn("追问模型未完成输出（{}），改用代码追问 node={}", outcome, id());
        }
        appendText(scope, text, codeQuestion);
        return text.toString();
    }

    /** 助手上一句话；没有历史时为"（无）" */
    protected static String lastAssistantMessage(FlowState state) {
        for (int i = state.history().size() - 1; i >= 0; i--) {
            HistoryMessage message = state.history().get(i);
            if (HistoryMessage.ASSISTANT.equals(message.role())) {
                return message.content();
            }
        }
        return "（无）";
    }

    /**
     * 有兜底的流式生成：第一个字在 firstTokenTimeout 内没到、出错或没有输出时，返回值告诉调用方改用固定文案。
     * text 里已有内容时（例如代码先输出的事实部分），模型输出另起一行接在后面。见 {@link NodeModelClient#streamGuarded}。
     */
    protected NodeModelClient.GuardedStream streamGuarded(NodeScope scope, String purpose, StreamingChatModel model,
                                                          ChatRequest request, StringBuilder text,
                                                          Duration firstTokenTimeout) {
        return support.modelClient().streamGuarded(scope, model, request,
                traceOptions(scope, purpose, true, null), text, firstTokenTimeout);
    }

    /** 往同一段回复里追加固定文本：text 为空时开始一段新回复，否则另起一行。用于和 {@link #streamGuarded} 拼接 */
    protected static void appendText(NodeScope scope, StringBuilder text, String s) {
        NodeModelClient.appendRoundText(scope, text, s);
    }

    /** 不经过模型的固定回复（例如确认文案、校验提示），同样以 token 事件推给前端 */
    protected String emitText(NodeScope scope, String text) {
        return support.modelClient().emitText(scope, text);
    }

    // =====================================================================
    // 工具循环
    // =====================================================================

    /**
     * tool loop 的结果。
     *
     * @param text     本节点输出给用户的全部文字（和推给前端的一致）
     * @param question 模型通过 ask_user 向用户提的问题；为 null 表示已经给出了最终回答
     */
    public record ToolLoopResult(String text, String question) {
        public boolean asked() {
            return question != null;
        }
    }

    /**
     * 带工具的流式对话（手写 tool loop）：
     * <ol>
     *   <li>把工具定义（外加内置的 ask_user）随请求发给模型，模型输出的文字实时推给前端</li>
     *   <li>模型要求调用工具时，逐个执行并把结果追加进对话（每次调用都落 flow_tool_call，并推送 tool 事件）</li>
     *   <li>重复，直到模型不再调用工具给出最终回答，或者调用 ask_user 向用户提问</li>
     * </ol>
     * 调用方拿到结果后：{@link ToolLoopResult#asked()} 为 true 时调用 {@link #awaitAnswer}，否则调用 {@link #closeAwait}。
     *
     * @param maxRounds 最多允许几轮工具调用，防止模型反复调用工具死循环
     */
    protected ToolLoopResult streamWithTools(NodeScope scope, String purpose, List<ChatMessage> messages,
                                             List<FlowTool> tools, int maxRounds) throws Exception {
        var result = support.toolLoopRunner().run(scope, purpose, messages, tools, maxRounds,
                (callPurpose, callId) -> traceOptions(scope, callPurpose, true, callId));
        return new ToolLoopResult(result.text(), result.question());
    }

    /** 把工具定义渲染成可读文本，给调试页面展示"这个节点挂了哪些工具" */
    protected static String describeTools(List<FlowTool> tools) {
        return ToolDescriptions.describe(tools);
    }

    // ------------------------------------------------------------------ 追问帧

    /**
     * 本节点向用户追问了，等用户回答：在任务栈顶放一个临时追问帧（本节点已有追问帧就更新问题）。
     * 下一轮路由看到它，用户回答了就回到本节点，换了话题就丢掉它。
     */
    protected void awaitAnswer(FlowState state, Map<String, Object> updates, String question) {
        List<TaskFrame> stack = new ArrayList<>();
        for (TaskFrame f : state.taskStack()) {
            if (f.isOpen() && !f.isEphemeral()) {
                stack.add(f.copy());
            }
        }
        String intent = routeSpec().map(RouteSpec::intent).orElse(id());
        stack.add(TaskFrame.awaitingAnswer(intent, id(), question));
        updates.put(FlowState.TASK_STACK, stack);
    }

    /** 本节点已经给出最终回答：如果之前在等用户回答，把追问帧去掉 */
    protected void closeAwait(FlowState state, Map<String, Object> updates) {
        if (state.taskStack().stream().noneMatch(TaskFrame::isEphemeral)) {
            return;
        }
        List<TaskFrame> stack = new ArrayList<>();
        for (TaskFrame f : state.taskStack()) {
            if (f.isOpen() && !f.isEphemeral()) {
                stack.add(f.copy());
            }
        }
        updates.put(FlowState.TASK_STACK, stack);
    }

    // =====================================================================
    // 历史与记忆
    // =====================================================================

    /**
     * 本节点要带多少记忆。默认值来自 flow.memory 配置：带摘要和长期记忆，不带工具结果。
     * 子类按需覆盖，例如路由只要最近几条、查询类节点要带工具结果：
     * <pre>
     * return super.historyPolicy().withMaxMessages(6).withToolResults(true);
     * </pre>
     */
    public HistoryPolicy historyPolicy() {
        return support.contextBuilder().defaultPolicy();
    }

    /** 按 {@link #historyPolicy()} 取历史消息，转换成 LangChain4j 消息 */
    protected List<ChatMessage> historyMessages(FlowState state) {
        return support.contextBuilder().toChatMessages(window(state, historyPolicy().maxMessages()));
    }

    /** 最多取最近 limit 条（同时受 token 上限约束） */
    protected List<ChatMessage> historyMessages(FlowState state, int limit) {
        return support.contextBuilder().toChatMessages(window(state, limit));
    }

    /** 历史消息渲染成纯文本，放进路由、抽取这类提示词里 */
    protected String historyAsText(FlowState state, int limit) {
        return support.contextBuilder().historyAsText(window(state, limit));
    }

    /**
     * 历史窗口：从最新的消息往前取，条数不超过 limit，token 不超过 historyPolicy().maxTokens()。
     * 至少保留最近一条（哪怕它自己就超了 token 上限），否则模型连上一句话都看不到。
     */
    protected List<HistoryMessage> window(FlowState state, int limit) {
        return support.contextBuilder().window(state, limit, historyPolicy());
    }

    /**
     * 记忆块：按 {@link #historyPolicy()} 拼出 长期记忆 / 早期对话摘要 / 最近查询到的数据，追加到节点的系统提示词后面。
     * 什么都没有时返回空字符串。
     */
    protected String memoryBlock(FlowState state) {
        HistoryPolicy policy = historyPolicy();
        return support.contextBuilder().memoryBlock(state, policy,
                policy.includeToolResults() ? window(state, policy.maxMessages()) : List.of());
    }

    // =====================================================================
    // 消息与状态辅助
    // =====================================================================

    protected static SystemMessage system(String text) {
        return SystemMessage.from(text);
    }

    /** 复制一份任务栈准备修改：推进阶段、完成或取消任务，改完调用 {@link TaskStackEdit#writeTo} 写回 */
    protected static TaskStackEdit editTasks(FlowState state) {
        return TaskStackEdit.of(state);
    }

    /** 新建一个可变的状态更新 Map（Map.of 不允许 null 值，容易踩坑） */
    protected static Map<String, Object> updates() {
        return new HashMap<>();
    }

    /** 本节点执行完后，在同一轮内继续执行 nodeId（例如任务从 C1 推进到 C2） */
    protected static Map<String, Object> goTo(Map<String, Object> updates, String nodeId) {
        updates.put(FlowState.NEXT_NODE, nodeId);
        return updates;
    }

    /** 只包含一条回复的状态更新 */
    protected static Map<String, Object> reply(String text) {
        Map<String, Object> u = updates();
        u.put(FlowState.REPLIES, text);
        return u;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 模型多次输出非法 JSON，保留此入口供已有节点捕获。 */
    public static class LlmJsonException extends com.example.smartflow.llm.LlmJsonException {
        public LlmJsonException(String purpose, String lastOutput, Throwable cause) {
            super(purpose, lastOutput, cause);
        }
    }
}

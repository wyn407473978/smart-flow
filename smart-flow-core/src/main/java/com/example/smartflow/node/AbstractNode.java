package com.example.smartflow.node;

import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.memory.TokenEstimator;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.llm.LlmJson;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.tool.FlowTool;
import com.example.smartflow.trace.TraceKeys;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 所有工作流节点的基类。
 * <p>
 * 统一负责：
 * <ul>
 *   <li>节点执行记录（flow_node_exec：输入状态、输出更新、耗时、异常）</li>
 *   <li>节点开始 / 结束事件推送</li>
 *   <li>模型调用：自动带上 trace 上下文，由 TraceChatModelListener 落库真实请求和响应</li>
 *   <li>JSON 结构化调用（解析失败自动纠错重试一次）</li>
 *   <li>流式回复（token 实时推给前端）</li>
 *   <li>历史消息转换、回复写入状态</li>
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
        TurnContext turn = support.turns().get(state.turnId());
        int seq = turn.nextNodeSeq(id());
        String execId = UUID.randomUUID().toString();
        long start = System.nanoTime();

        support.trace().insertNodeExec(execId, turn.turnId(), turn.sessionId(), seq,
                id(), type().name(), inputSnapshot(state));
        turn.emit(TurnEvent.of("node_start", "node", id(), "type", type().name(), "seq", seq));

        try {
            Map<String, Object> result = execute(state, new NodeScope(turn, execId, id()));
            Map<String, Object> updates = result == null ? new HashMap<>() : new HashMap<>(result);
            // 没有指定下一个节点就结束本轮；必须每次都写，否则会沿用上一个节点留下的值
            updates.putIfAbsent(FlowState.NEXT_NODE, FlowNames.END_TURN);
            long ms = elapsedMs(start);
            support.trace().finishNodeExec(execId, updates, "SUCCESS", null, ms);
            turn.emit(TurnEvent.of("node_end", "node", id(), "status", "SUCCESS", "latencyMs", ms));
            return updates;
        } catch (Exception e) {
            long ms = elapsedMs(start);
            log.error("节点执行失败 node={} turn={}", id(), turn.turnId(), e);
            support.trace().finishNodeExec(execId, null, "ERROR",
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
        ChatRequestOptions.Builder b = ChatRequestOptions.builder()
                .addListenerAttribute(TraceKeys.TURN_ID, scope.turn().turnId())
                .addListenerAttribute(TraceKeys.SESSION_ID, scope.turn().sessionId())
                .addListenerAttribute(TraceKeys.EXEC_ID, scope.execId())
                .addListenerAttribute(TraceKeys.NODE_ID, scope.nodeId())
                .addListenerAttribute(TraceKeys.PURPOSE, purpose)
                .addListenerAttribute(TraceKeys.STREAMING, streaming);
        if (callId != null) {
            b.addListenerAttribute(TraceKeys.CALL_ID, callId);
        }
        return b.build();
    }

    /** 普通（非流式）调用 */
    protected ChatResponse chat(NodeScope scope, String purpose, ChatModel model, ChatRequest request) {
        return model.chat(request, traceOptions(scope, purpose, false));
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
        List<ChatMessage> msgs = new ArrayList<>(messages);
        Exception last = null;
        String lastText = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(msgs)
                    .responseFormat(ResponseFormat.JSON)
                    .temperature(0.0)
                    .build();
            ChatResponse response = chat(scope, attempt == 1 ? purpose : purpose + "#retry", model, request);
            lastText = response.aiMessage().text();
            try {
                return support.llmJsonMapper().readValue(stripCodeFence(lastText), type);
            } catch (Exception e) {
                last = e;
                log.warn("模型 JSON 解析失败 node={} purpose={} attempt={} text={}", id(), purpose, attempt, lastText);
                msgs.add(response.aiMessage());
                msgs.add(UserMessage.from("你上面的输出不是合法的 JSON。请只输出符合要求的 JSON 对象，不要包含任何其他文字。"));
            }
        }
        throw new LlmJsonException(purpose, lastText, last);
    }

    /**
     * 流式生成回复：token 实时推给前端，返回完整文本。调用方负责把返回值写进状态（{@link #reply(String)}）。
     */
    protected String streamReply(NodeScope scope, String purpose, List<ChatMessage> messages) throws Exception {
        StringBuilder text = new StringBuilder();
        streamChat(scope, purpose, ChatRequest.builder().messages(messages).build(), null, text);
        return text.toString();
    }

    /** 内置的"向用户提问"工具名 */
    public static final String ASK_USER = "ask_user";

    /**
     * 内置工具：模型需要用户补充或确认信息时调用它，而不是直接在回复里提问。
     * 这样代码能确切知道"这个节点在等用户回答"，下一轮路由会把用户的回答送回这个节点。
     */
    private static final ToolSpecification ASK_USER_SPEC = ToolSpecification.builder()
            .name(ASK_USER)
            .description("需要用户补充或确认信息才能继续时调用（例如查天气不知道城市、需要用户确认是不是某个城市）。"
                    + "question 是要对用户说的话。调用后本轮结束，等用户回答。"
                    + "调用这个工具时不要再输出其他文字，也不要在普通回复里向用户提问。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("question", "要问用户的话，简短、口语化")
                    .required("question")
                    .build())
            .build();

    /** 只用来让 ask_user 和普通工具走同一套落库逻辑 */
    private static final FlowTool ASK_USER_TOOL = new FlowTool() {
        @Override
        public ToolSpecification specification() {
            return ASK_USER_SPEC;
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolContext context) {
            return "{\"status\":\"已向用户提问，等待回答\"}";
        }
    };

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
        Map<String, FlowTool> byName = new LinkedHashMap<>();
        for (FlowTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        byName.put(ASK_USER, ASK_USER_TOOL);
        List<ToolSpecification> specs = byName.values().stream().map(FlowTool::specification).toList();

        List<ChatMessage> msgs = new ArrayList<>(messages);
        StringBuilder text = new StringBuilder();
        for (int round = 1; round <= maxRounds; round++) {
            String callId = UUID.randomUUID().toString();
            int roundStart = text.length();
            ChatRequest request = ChatRequest.builder().messages(msgs).toolSpecifications(specs).build();
            AiMessage ai = streamChat(scope, round == 1 ? purpose : purpose + "#" + round, request, callId, text)
                    .aiMessage();
            if (!ai.hasToolExecutionRequests()) {
                return new ToolLoopResult(text.toString(), null);
            }

            Optional<ToolExecutionRequest> ask = ai.toolExecutionRequests().stream()
                    .filter(c -> ASK_USER.equals(c.name())).findFirst();
            if (ask.isPresent()) {
                if (ai.toolExecutionRequests().size() > 1) {
                    log.warn("节点 {} 在同一轮里既调用了 ask_user 又调用了其他工具，其他工具被忽略", id());
                }
                runTool(scope, callId, ask.get(), byName);
                String roundText = text.substring(roundStart).strip();
                String question = askUserQuestion(ask.get());
                if (roundText.isEmpty()) {
                    // 正常情况：问题在工具参数里，推给用户
                    appendRoundText(scope, text, question);
                } else if (question.isBlank()) {
                    // 模型把问题写在了正文里
                    question = roundText;
                }
                return new ToolLoopResult(text.toString(), question.isBlank() ? text.toString().strip() : question);
            }

            msgs.add(ai);
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                msgs.add(ToolExecutionResultMessage.from(call, runTool(scope, callId, call, byName)));
            }
        }
        log.warn("节点 {} 工具调用超过 {} 轮，强制结束", id(), maxRounds);
        String giveUp = "抱歉，这个问题需要的查询步骤太多，我暂时没能完成，可以换个问法再试试。";
        appendRoundText(scope, text, giveUp);
        return new ToolLoopResult(text.toString(), null);
    }

    private String askUserQuestion(ToolExecutionRequest call) {
        try {
            Map<String, Object> args = support.llmJsonMapper().readValue(blankToEmptyJson(call.arguments()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object q = args == null ? null : args.get("question");
            return q == null ? "" : q.toString().strip();
        } catch (Exception e) {
            return "";
        }
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

    /** 执行一次工具调用并落库。工具出错时把错误信息返回给模型，让它自己决定怎么回复用户 */
    private String runTool(NodeScope scope, String llmCallId, ToolExecutionRequest call, Map<String, FlowTool> tools) {
        String toolCallId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        support.trace().insertToolCall(toolCallId, scope.turn().turnId(), scope.turn().sessionId(), scope.execId(),
                llmCallId, id(), call.name(), blankToEmptyJson(call.arguments()));
        String status = "SUCCESS";
        String error = null;
        String result;
        try {
            FlowTool tool = tools.get(call.name());
            if (tool == null) {
                throw new IllegalArgumentException("工具不存在: " + call.name());
            }
            Map<String, Object> args = support.llmJsonMapper().readValue(blankToEmptyJson(call.arguments()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            result = tool.execute(args == null ? Map.of() : args,
                    new FlowTool.ToolContext(scope.turn().sessionId(), scope.turn().turnId()));
        } catch (Exception e) {
            log.warn("工具执行失败 node={} tool={} args={}", id(), call.name(), call.arguments(), e);
            status = "ERROR";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            result = "{\"error\":\"工具执行失败：" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        support.trace().finishToolCall(toolCallId, result, status, error, ms);
        scope.turn().emit(TurnEvent.of("tool", "node", id(), "name", call.name(),
                "arguments", call.arguments(), "result", result, "status", status, "latencyMs", ms));
        return result;
    }

    /**
     * 一次流式调用。token 实时推给前端，并追加到 text；同一个节点多轮输出之间用换行隔开。
     *
     * @param callId 预先指定的模型调用 ID（工具调用要关联到它），为 null 时由 listener 生成
     */
    private ChatResponse streamChat(NodeScope scope, String purpose, ChatRequest request, String callId,
                                    StringBuilder text) throws Exception {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ChatRequestOptions options = traceOptions(scope, purpose, true, callId);
        boolean[] first = {true};
        support.models().streaming().chat(request, options, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                if (first[0]) {
                    first[0] = false;
                    beginRound(scope, text);
                }
                text.append(partial);
                scope.turn().emitToken(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        int timeout = support.props().model().timeoutSeconds() == null ? 60 : support.props().model().timeoutSeconds();
        return done.get(timeout + 10L, TimeUnit.SECONDS);
    }

    /** 本节点开始输出新一段文字：第一段前处理节点间分隔，后续段前加换行 */
    private static void beginRound(NodeScope scope, StringBuilder text) {
        if (text.isEmpty()) {
            scope.turn().beginReply();
        } else if (text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
            scope.turn().emitToken("\n");
        }
    }

    private static void appendRoundText(NodeScope scope, StringBuilder text, String s) {
        beginRound(scope, text);
        text.append(s);
        scope.turn().emitToken(s);
    }

    /** 把工具定义渲染成可读文本，给调试页面展示"这个节点挂了哪些工具" */
    protected static String describeTools(List<FlowTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (FlowTool tool : tools) {
            ToolSpecification spec = tool.specification();
            sb.append(spec.name()).append("：").append(spec.description()).append('\n');
            JsonObjectSchema params = spec.parameters();
            if (params == null || params.properties() == null || params.properties().isEmpty()) {
                sb.append("  参数：无\n");
            } else {
                List<String> required = params.required() == null ? List.of() : params.required();
                params.properties().forEach((name, schema) -> sb.append("  - ").append(name)
                        .append(required.contains(name) ? "（必填）" : "（可选）")
                        .append("：").append(Optional.ofNullable(schema.description()).orElse("")).append('\n'));
            }
            sb.append('\n');
        }
        sb.append(ASK_USER).append("（内置，streamWithTools 自动挂载）：").append(ASK_USER_SPEC.description());
        return sb.toString().stripTrailing();
    }

    private static String blankToEmptyJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    /** 不经过模型的固定回复（例如确认文案、校验提示），同样以 token 事件推给前端 */
    protected String emitText(NodeScope scope, String text) {
        scope.turn().beginReply();
        scope.turn().emitToken(text);
        return text;
    }

    // =====================================================================
    // 消息与状态辅助
    // =====================================================================

    // ------------------------------------------------------------------ 记忆

    /** 工具结果放进提示词时每条最多保留的字数，避免一次查询返回大段数据把上下文撑爆 */
    private static final int TOOL_RESULT_MAX_CHARS = 500;

    /**
     * 本节点要带多少记忆。默认值来自 flow.memory 配置：带摘要和长期记忆，不带工具结果。
     * 子类按需覆盖，例如路由只要最近几条、查询类节点要带工具结果：
     * <pre>
     * return super.historyPolicy().withMaxMessages(6).withToolResults(true);
     * </pre>
     */
    public HistoryPolicy historyPolicy() {
        var m = support.props().memory();
        return new HistoryPolicy(m.recentMessages(), m.maxHistoryTokens(), true, true, false);
    }

    /** 按 {@link #historyPolicy()} 取历史消息，转换成 LangChain4j 消息 */
    protected List<ChatMessage> historyMessages(FlowState state) {
        return toChatMessages(window(state, historyPolicy().maxMessages()));
    }

    /** 最多取最近 limit 条（同时受 token 上限约束） */
    protected List<ChatMessage> historyMessages(FlowState state, int limit) {
        return toChatMessages(window(state, limit));
    }

    /** 历史消息渲染成纯文本，放进路由、抽取这类提示词里 */
    protected String historyAsText(FlowState state, int limit) {
        StringBuilder sb = new StringBuilder();
        for (HistoryMessage m : window(state, limit)) {
            sb.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        return sb.isEmpty() ? "（无）" : sb.toString();
    }

    /**
     * 历史窗口：从最新的消息往前取，条数不超过 limit，token 不超过 historyPolicy().maxTokens()。
     * 至少保留最近一条（哪怕它自己就超了 token 上限），否则模型连上一句话都看不到。
     */
    protected List<HistoryMessage> window(FlowState state, int limit) {
        List<HistoryMessage> history = state.history();
        int budget = historyPolicy().maxTokens();
        TokenEstimator estimator = support.tokenEstimator();
        int used = 0;
        int from = history.size();
        while (from > 0 && history.size() - from < limit) {
            int cost = estimator.estimate(history.get(from - 1).content());
            if (from < history.size() && used + cost > budget) {
                break;
            }
            used += cost;
            from--;
        }
        return history.subList(from, history.size());
    }

    private static List<ChatMessage> toChatMessages(List<HistoryMessage> messages) {
        List<ChatMessage> out = new ArrayList<>();
        for (HistoryMessage m : messages) {
            out.add(m.isUser() ? UserMessage.from(m.content()) : AiMessage.from(m.content()));
        }
        return out;
    }

    /**
     * 记忆块：按 {@link #historyPolicy()} 拼出 长期记忆 / 早期对话摘要 / 最近查询到的数据，追加到节点的系统提示词后面。
     * 什么都没有时返回空字符串。
     */
    protected String memoryBlock(FlowState state) {
        HistoryPolicy p = historyPolicy();
        StringBuilder sb = new StringBuilder();
        if (p.includeUserMemory() && !state.userMemory().isEmpty()) {
            sb.append("\n【关于用户的长期信息】（来自以往的对话，可能已经变化；和本轮对话冲突时以本轮为准，不要主动复述这些信息）\n");
            for (MemoryFact f : state.userMemory()) {
                sb.append("- ").append(f.key()).append("：").append(f.value()).append('\n');
            }
        }
        if (p.includeSummary() && !state.summary().isBlank()) {
            sb.append("\n【早期对话摘要】\n").append(state.summary().strip()).append('\n');
        }
        if (p.includeToolResults()) {
            Set<String> inWindow = new HashSet<>();
            for (HistoryMessage m : window(state, p.maxMessages())) {
                if (m.turnId() != null) {
                    inWindow.add(m.turnId());
                }
            }
            List<ToolFact> facts = state.toolFacts().stream().filter(f -> inWindow.contains(f.turnId())).toList();
            if (!facts.isEmpty()) {
                sb.append("\n【最近查询到的数据】（之前的对话里查到的，用户追问时可以直接引用；需要新的数据时再调用工具）\n");
                for (ToolFact f : facts) {
                    sb.append("- ").append(f.toolName()).append('(').append(f.arguments() == null ? "" : f.arguments())
                            .append(") → ").append(truncate(f.result(), TOOL_RESULT_MAX_CHARS)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

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

    private static String stripCodeFence(String text) {
        return LlmJson.stripCodeFence(text);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 模型多次输出非法 JSON */
    public static class LlmJsonException extends RuntimeException {
        public LlmJsonException(String purpose, String lastOutput, Throwable cause) {
            super("模型输出无法解析为 JSON, purpose=" + purpose + ", output=" + lastOutput, cause);
        }
    }
}

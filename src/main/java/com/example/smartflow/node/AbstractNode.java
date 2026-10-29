package com.example.smartflow.node;

import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * 带工具的流式对话（手写 tool loop）：
     * <ol>
     *   <li>把工具定义随请求发给模型，模型输出的文字实时推给前端</li>
     *   <li>模型要求调用工具时，逐个执行并把结果追加进对话（每次调用都落 flow_tool_call，并推送 tool 事件）</li>
     *   <li>重复，直到模型不再调用工具，给出最终回答</li>
     * </ol>
     *
     * @param maxRounds 最多允许几轮工具调用，防止模型反复调用工具死循环
     * @return 本节点输出给用户的全部文字（各轮文字按顺序拼接，和推给前端的一致）
     */
    protected String streamWithTools(NodeScope scope, String purpose, List<ChatMessage> messages,
                                     List<FlowTool> tools, int maxRounds) throws Exception {
        Map<String, FlowTool> byName = new LinkedHashMap<>();
        for (FlowTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        List<ToolSpecification> specs = tools.stream().map(FlowTool::specification).toList();

        List<ChatMessage> msgs = new ArrayList<>(messages);
        StringBuilder text = new StringBuilder();
        for (int round = 1; round <= maxRounds; round++) {
            String callId = UUID.randomUUID().toString();
            ChatRequest request = ChatRequest.builder().messages(msgs).toolSpecifications(specs).build();
            AiMessage ai = streamChat(scope, round == 1 ? purpose : purpose + "#" + round, request, callId, text)
                    .aiMessage();
            if (!ai.hasToolExecutionRequests()) {
                return text.toString();
            }
            msgs.add(ai);
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                msgs.add(ToolExecutionResultMessage.from(call, runTool(scope, callId, call, byName)));
            }
        }
        log.warn("节点 {} 工具调用超过 {} 轮，强制结束", id(), maxRounds);
        String giveUp = "抱歉，这个问题需要的查询步骤太多，我暂时没能完成，可以换个问法再试试。";
        appendRoundText(scope, text, giveUp);
        return text.toString();
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

    /** 把短期记忆转换成 LangChain4j 消息，最多取最近 limit 条 */
    protected List<ChatMessage> historyMessages(FlowState state, int limit) {
        List<HistoryMessage> history = state.history();
        int from = Math.max(0, history.size() - limit);
        List<ChatMessage> out = new ArrayList<>();
        for (HistoryMessage m : history.subList(from, history.size())) {
            out.add(m.isUser() ? UserMessage.from(m.content()) : AiMessage.from(m.content()));
        }
        return out;
    }

    /** 历史消息渲染成纯文本，放进路由、抽取这类提示词里 */
    protected String historyAsText(FlowState state, int limit) {
        List<HistoryMessage> history = state.history();
        int from = Math.max(0, history.size() - limit);
        StringBuilder sb = new StringBuilder();
        for (HistoryMessage m : history.subList(from, history.size())) {
            sb.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        return sb.isEmpty() ? "（无）" : sb.toString();
    }

    protected static SystemMessage system(String text) {
        return SystemMessage.from(text);
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
        if (text == null) {
            return "";
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return t;
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

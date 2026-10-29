package com.example.smartflow.node;

import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnEvent;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.trace.TraceKeys;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
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
        return ChatRequestOptions.builder()
                .addListenerAttribute(TraceKeys.TURN_ID, scope.turn().turnId())
                .addListenerAttribute(TraceKeys.SESSION_ID, scope.turn().sessionId())
                .addListenerAttribute(TraceKeys.EXEC_ID, scope.execId())
                .addListenerAttribute(TraceKeys.NODE_ID, scope.nodeId())
                .addListenerAttribute(TraceKeys.PURPOSE, purpose)
                .addListenerAttribute(TraceKeys.STREAMING, streaming)
                .build();
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
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        scope.turn().beginReply();
        support.models().streaming().chat(request, traceOptions(scope, purpose, true),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partial) {
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
        ChatResponse response = done.get(timeout + 10L, TimeUnit.SECONDS);
        return response.aiMessage().text();
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

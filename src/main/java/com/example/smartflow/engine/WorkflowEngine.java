package com.example.smartflow.engine;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.session.SessionRepository;
import com.example.smartflow.session.SessionRepository.SessionRecord;
import com.example.smartflow.session.SessionState;
import com.example.smartflow.trace.TraceRepository;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 每轮对话的入口：加载会话 → 跑一遍图 → 保存会话和 trace。
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final CompiledGraph<FlowState> graph;
    private final SessionRepository sessions;
    private final TraceRepository trace;
    private final TurnRegistry turns;
    private final FlowProperties props;
    private final RouteCatalog catalog;

    /**
     * 会话锁：同一会话同一时间只处理一条消息。
     * 单机版用 JVM 锁，多实例部署时换成 Redis 分布式锁。
     */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public WorkflowEngine(CompiledGraph<FlowState> graph, SessionRepository sessions, TraceRepository trace,
                          TurnRegistry turns, FlowProperties props, RouteCatalog catalog) {
        this.catalog = catalog;
        this.graph = graph;
        this.sessions = sessions;
        this.trace = trace;
        this.turns = turns;
        this.props = props;
    }

    public record TurnResult(String turnId, String reply, List<String> nodePath, SessionState state) {
    }

    /**
     * 处理一条用户消息。阻塞直到本轮结束，过程中的事件通过 sink 推出去。
     */
    public TurnResult handle(String sessionId, String userInput, Consumer<TurnEvent> sink) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("该会话上一条消息还在处理中，请稍后再发");
        }
        try {
            return doHandle(sessionId, userInput, sink);
        } finally {
            lock.unlock();
        }
    }

    private TurnResult doHandle(String sessionId, String userInput, Consumer<TurnEvent> sink) {
        long start = System.nanoTime();

        // 1. 加载会话
        sessions.create(sessionId, null);
        SessionRecord session = sessions.find(sessionId).orElseThrow();
        SessionState before = session.state();
        int seq = session.turnCount() + 1;

        String turnId = UUID.randomUUID().toString();
        trace.insertTurn(turnId, sessionId, seq, userInput, before);

        // 2. 短期记忆：先读历史，再写入本轮用户消息（历史里不含本轮输入）
        List<HistoryMessage> history = sessions.recentMessages(sessionId, props.memory().recentMessages());
        sessions.insertMessage(sessionId, turnId, HistoryMessage.USER, userInput, null);

        TurnContext ctx = new TurnContext(sessionId, turnId, sink);
        turns.register(ctx);
        ctx.emit(TurnEvent.of("turn", "turnId", turnId, "seq", seq));

        try {
            // 3. 跑图
            Map<String, Object> input = new HashMap<>();
            input.put(FlowState.SESSION_ID, sessionId);
            input.put(FlowState.TURN_ID, turnId);
            input.put(FlowState.USER_INPUT, userInput);
            input.put(FlowState.HISTORY, new ArrayList<>(history));
            input.put(FlowState.SUMMARY, before.getSummary());
            input.put(FlowState.TASK_STACK, new ArrayList<>(before.getTaskStack()));

            FlowState finalState = null;
            for (NodeOutput<FlowState> output : graph.stream(input)) {
                finalState = output.state();
            }
            if (finalState == null) {
                throw new IllegalStateException("图没有产生任何输出");
            }

            // 4. 组装回复：节点回复 + 插话后的任务恢复提示
            List<String> parts = new ArrayList<>(finalState.replies());
            resumeHint(finalState, ctx).ifPresent(hint -> {
                ctx.beginReply();
                ctx.emitToken(hint);
                parts.add(hint);
            });
            String reply = String.join(TurnContext.REPLY_SEPARATOR, parts);

            // 5. 保存
            SessionState after = new SessionState();
            after.setTaskStack(openTasks(finalState.taskStack()));
            after.setSummary(finalState.summary());
            sessions.save(sessionId, after, seq);
            if (!reply.isBlank()) {
                List<String> path = ctx.nodePath();
                sessions.insertMessage(sessionId, turnId, HistoryMessage.ASSISTANT, reply,
                        path.isEmpty() ? null : path.get(path.size() - 1));
            }

            RouteDecision route = ctx.route();
            long ms = (System.nanoTime() - start) / 1_000_000;
            trace.finishTurn(turnId, reply,
                    route == null ? null : route.action().name(),
                    route == null ? null : route.target(),
                    ctx.nodePath(), after, "SUCCESS", null, ms);

            ctx.emit(TurnEvent.of("done", "turnId", turnId, "reply", reply,
                    "nodePath", ctx.nodePath(), "state", after, "latencyMs", ms));
            return new TurnResult(turnId, reply, ctx.nodePath(), after);

        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Throwable root = rootCause(e);
            log.error("本轮处理失败 session={} turn={}", sessionId, turnId, e);
            // 状态回滚到本轮开始前，但轮次号照常递增，避免下一轮 seq 重复
            sessions.save(sessionId, before, seq);
            trace.finishTurn(turnId, null, null, null, ctx.nodePath(), before, "ERROR",
                    root.getClass().getSimpleName() + ": " + root.getMessage(), ms);
            ctx.emit(TurnEvent.of("error", "turnId", turnId, "message", root.getMessage()));
            throw new TurnFailedException(turnId, root);
        } finally {
            turns.unregister(turnId);
        }
    }

    /**
     * 用户在任务进行中插话（闲聊、业务查询），回答完以后提醒一下还有未完成的任务。
     * 用固定文案而不是让模型生成，保证每次都出现、内容可控。
     */
    private Optional<String> resumeHint(FlowState state, TurnContext ctx) {
        RouteDecision route = ctx.route();
        if (route == null || route.action() != RouteDecision.Action.DIGRESS) {
            return Optional.empty();
        }
        return state.activeTask().map(t -> "对了，刚才的" + catalog.taskName(t.getTaskType()) + "还没完成，要继续吗？");
    }

    private static List<TaskFrame> openTasks(List<TaskFrame> stack) {
        return stack.stream().filter(TaskFrame::isOpen).toList();
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    public static class TurnFailedException extends RuntimeException {
        private final String turnId;

        public TurnFailedException(String turnId, Throwable cause) {
            super(cause.getMessage(), cause);
            this.turnId = turnId;
        }

        public String turnId() {
            return turnId;
        }
    }
}

package com.example.smartflow.engine;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.memory.MemoryService;
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
    private final MemoryService memory;

    /**
     * 会话锁：同一会话同一时间只处理一条消息。
     * 单机版用 JVM 锁，多实例部署时换成 Redis 分布式锁。
     */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public WorkflowEngine(CompiledGraph<FlowState> graph, SessionRepository sessions, TraceRepository trace,
                          TurnRegistry turns, FlowProperties props, RouteCatalog catalog, MemoryService memory) {
        this.catalog = catalog;
        this.memory = memory;
        this.graph = graph;
        this.sessions = sessions;
        this.trace = trace;
        this.turns = turns;
        this.props = props;
    }

    public record TurnResult(String turnId, String reply, List<String> nodePath, SessionState state) {
    }

    /** 没有用户标识的调用（不启用长期记忆） */
    public TurnResult handle(String sessionId, String userInput, Consumer<TurnEvent> sink) {
        return handle(sessionId, null, userInput, sink);
    }

    /**
     * 处理一条用户消息。阻塞直到本轮结束，过程中的事件通过 sink 推出去。
     *
     * @param userId 用户标识，可以为空；会话第一次带上 userId 时绑定，之后不传也会沿用会话绑定的用户
     */
    public TurnResult handle(String sessionId, String userId, String userInput, Consumer<TurnEvent> sink) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("该会话上一条消息还在处理中，请稍后再发");
        }
        try {
            return doHandle(sessionId, userId, userInput, sink);
        } finally {
            lock.unlock();
        }
    }

    private TurnResult doHandle(String sessionId, String requestUserId, String userInput, Consumer<TurnEvent> sink) {
        long start = System.nanoTime();

        // 1. 加载会话
        sessions.create(sessionId, blankToNull(requestUserId));
        SessionRecord session = sessions.find(sessionId).orElseThrow();
        SessionState before = session.state();
        String userId = blankToNull(session.userId()) != null ? session.userId() : blankToNull(requestUserId);
        int seq = session.turnCount() + 1;

        String turnId = UUID.randomUUID().toString();
        trace.insertTurn(turnId, sessionId, seq, userInput, before);

        // 2. 记忆：摘要 + 摘要之后的对话原文 + 其中的工具结果 + 长期记忆。先读再写入本轮用户消息（历史里不含本轮输入）
        MemoryService.MemoryContext mem = memory.load(sessionId, userId);
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
            if (userId != null) {
                input.put(FlowState.USER_ID, userId);
            }
            input.put(FlowState.HISTORY, new ArrayList<>(mem.history()));
            input.put(FlowState.SUMMARY, mem.summary());
            input.put(FlowState.TOOL_FACTS, new ArrayList<>(mem.toolFacts()));
            input.put(FlowState.USER_MEMORY, new ArrayList<>(mem.userMemories()));
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
            resumeHint(finalState, ctx, String.join("", parts)).ifPresent(hint -> {
                ctx.beginReply();
                ctx.emitToken(hint);
                parts.add(hint);
            });
            String reply = String.join(TurnContext.REPLY_SEPARATOR, parts);

            // 5. 保存
            SessionState after = new SessionState();
            after.setTaskStack(openTasks(finalState.taskStack()));
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

            // 6. 后台记忆任务（滚动摘要、长期记忆抽取），不占用户等待时间
            memory.afterTurn(sessionId, userId, turnId, userInput, reply);
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
     * 用户在任务进行中插话（闲聊、业务查询），处理完以后提醒一下还有未完成的任务。
     * <ul>
     *   <li>插话的节点还在追问用户（例如问城市）时不提醒，否则一条回复里会有两个问题，用户回答"是的"时分不清在回答哪个</li>
     *   <li>节点的回复里已经自己提醒过了（提示词里要求了不要提醒，但模型不一定听）就不再重复</li>
     * </ul>
     * 用固定文案而不是让模型生成，保证每次都出现、内容可控。
     */
    private Optional<String> resumeHint(FlowState state, TurnContext ctx, String replySoFar) {
        RouteDecision route = ctx.route();
        if (route == null || !route.sideTrip() || state.pendingQuestion().isPresent()) {
            return Optional.empty();
        }
        return state.activeTask()
                .map(t -> catalog.taskName(t.getTaskType()))
                .filter(name -> !alreadyReminded(replySoFar, name))
                .map(name -> "对了，刚才的" + name + "还没完成，要继续吗？");
    }

    /** 回复里同时提到了任务名和"继续"，就认为节点已经自己提醒过了 */
    static boolean alreadyReminded(String reply, String taskName) {
        return reply != null && reply.contains(taskName) && reply.contains("继续");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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

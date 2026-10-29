package com.example.smartflow.engine;

import com.example.smartflow.flow.RouteDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 一轮对话的运行时上下文：事件推送通道、节点计数、节点路径。
 * <p>
 * 这些东西不可序列化，不能放进 LangGraph4j 的状态里，所以按 turnId 放在 {@link TurnRegistry} 中，
 * 节点通过状态里的 turnId 取到它。
 */
public class TurnContext {

    private final String sessionId;
    private final String turnId;
    private final Consumer<TurnEvent> sink;
    private final AtomicInteger nodeSeq = new AtomicInteger();
    private final List<String> nodePath = Collections.synchronizedList(new ArrayList<>());
    private volatile RouteDecision route;
    private final AtomicBoolean hasReply = new AtomicBoolean();

    /** 同一轮多个节点回复之间的分隔 */
    public static final String REPLY_SEPARATOR = "\n\n";

    public TurnContext(String sessionId, String turnId, Consumer<TurnEvent> sink) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.sink = sink;
    }

    public void emit(TurnEvent event) {
        sink.accept(event);
    }

    public void emitToken(String text) {
        if (text != null && !text.isEmpty()) {
            sink.accept(TurnEvent.token(text));
        }
    }

    /**
     * 一个节点开始输出回复前调用。同一轮里如果前面已经有节点回复过，先推一个段落分隔，
     * 保证前端看到的内容和最终落库的回复（用空行拼接）一致。
     */
    public void beginReply() {
        if (hasReply.getAndSet(true)) {
            sink.accept(TurnEvent.token(REPLY_SEPARATOR));
        }
    }

    public int nextNodeSeq(String nodeId) {
        nodePath.add(nodeId);
        return nodeSeq.incrementAndGet();
    }

    public List<String> nodePath() {
        synchronized (nodePath) {
            return List.copyOf(nodePath);
        }
    }

    public String sessionId() { return sessionId; }
    public String turnId() { return turnId; }

    public RouteDecision route() { return route; }
    public void route(RouteDecision route) { this.route = route; }
}

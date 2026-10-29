package com.example.smartflow.trace;

/**
 * 通过 LangChain4j 的 ChatRequestOptions.listenerAttributes 把节点上下文传给 {@link TraceChatModelListener}。
 * 这样不依赖 ThreadLocal，流式回调跑在别的线程上也能关联到正确的节点。
 */
public final class TraceKeys {

    private TraceKeys() {
    }

    public static final String TURN_ID = "flow.turnId";
    public static final String SESSION_ID = "flow.sessionId";
    public static final String EXEC_ID = "flow.execId";
    public static final String NODE_ID = "flow.nodeId";
    public static final String PURPOSE = "flow.purpose";
    public static final String STREAMING = "flow.streaming";

    // listener 内部使用
    static final String CALL_ID = "flow.callId";
    static final String START_NANOS = "flow.startNanos";
}

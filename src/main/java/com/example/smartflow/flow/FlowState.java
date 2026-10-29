package com.example.smartflow.flow;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 图在一轮内流转的状态。
 * <p>
 * 跨轮的数据（任务栈、摘要、历史消息）由 {@code WorkflowEngine} 在每轮开始时从数据库读出来放进来，
 * 结束时再写回去；图本身每轮都从 START 跑到 END。
 * <p>
 * 注意：放进状态的值必须实现 {@link java.io.Serializable}，LangGraph4j 会在节点之间克隆状态。
 */
public class FlowState extends AgentState {

    // ---- 每轮输入 ----
    public static final String SESSION_ID = "sessionId";
    public static final String TURN_ID = "turnId";
    public static final String USER_INPUT = "userInput";

    // ---- 跨轮数据（来自数据库）----
    /** List&lt;HistoryMessage&gt;，不含本轮用户输入 */
    public static final String HISTORY = "history";
    public static final String SUMMARY = "summary";
    /** List&lt;TaskFrame&gt;，最后一个元素是栈顶 */
    public static final String TASK_STACK = "taskStack";

    // ---- 本轮产生 ----
    public static final String ROUTE = "route";
    /** List&lt;String&gt;，本轮各节点产出的回复片段，按顺序追加 */
    public static final String REPLIES = "replies";

    /** 只有 REPLIES 需要追加语义，其余 key 默认覆盖 */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            REPLIES, Channels.<String>appenderWithDuplicate(ArrayList::new)
    );

    public FlowState(Map<String, Object> initData) {
        super(initData);
    }

    public String sessionId() {
        return this.<String>value(SESSION_ID).orElseThrow();
    }

    public String turnId() {
        return this.<String>value(TURN_ID).orElseThrow();
    }

    public String userInput() {
        return this.<String>value(USER_INPUT).orElse("");
    }

    public List<HistoryMessage> history() {
        return this.<List<HistoryMessage>>value(HISTORY).orElse(List.of());
    }

    public String summary() {
        return this.<String>value(SUMMARY).orElse("");
    }

    public List<TaskFrame> taskStack() {
        return this.<List<TaskFrame>>value(TASK_STACK).orElse(List.of());
    }

    /** 栈顶第一个仍在进行中的任务 */
    public Optional<TaskFrame> activeTask() {
        List<TaskFrame> stack = taskStack();
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).isOpen()) {
                return Optional.of(stack.get(i));
            }
        }
        return Optional.empty();
    }

    public Optional<RouteDecision> route() {
        return value(ROUTE);
    }

    public List<String> replies() {
        return this.<List<String>>value(REPLIES).orElse(List.of());
    }
}

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
    /** 可以为空：没有用户标识时不启用长期记忆 */
    public static final String USER_ID = "userId";

    // ---- 跨轮数据（来自数据库）----
    /** List&lt;HistoryMessage&gt;，不含本轮用户输入 */
    public static final String HISTORY = "history";
    public static final String SUMMARY = "summary";
    /** List&lt;ToolFact&gt;，历史窗口覆盖的轮次里成功的工具调用 */
    public static final String TOOL_FACTS = "toolFacts";
    /** List&lt;MemoryFact&gt;，用户长期记忆 */
    public static final String USER_MEMORY = "userMemory";
    /** List&lt;TaskFrame&gt;，最后一个元素是栈顶 */
    public static final String TASK_STACK = "taskStack";

    // ---- 本轮产生 ----
    public static final String ROUTE = "route";
    /**
     * 当前节点执行完后要去的节点（本轮内）。每个节点执行完都会写这个 key，
     * 没写的话基类会补成 {@link FlowNames#END_TURN}，所以图里所有节点共用同一个条件边。
     */
    public static final String NEXT_NODE = "nextNode";
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

    public String userId() {
        return this.<String>value(USER_ID).orElse(null);
    }

    public List<ToolFact> toolFacts() {
        return this.<List<ToolFact>>value(TOOL_FACTS).orElse(List.of());
    }

    public List<MemoryFact> userMemory() {
        return this.<List<MemoryFact>>value(USER_MEMORY).orElse(List.of());
    }

    public List<TaskFrame> taskStack() {
        return this.<List<TaskFrame>>value(TASK_STACK).orElse(List.of());
    }

    /** 最靠近栈顶的、仍在进行中的多轮任务（不含临时追问帧） */
    public Optional<TaskFrame> activeTask() {
        List<TaskFrame> stack = taskStack();
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).isOpen() && !stack.get(i).isEphemeral()) {
                return Optional.of(stack.get(i));
            }
        }
        return Optional.empty();
    }

    /** 栈顶的临时追问帧：某个一次性节点上一轮向用户提了问题，正在等回答 */
    public Optional<TaskFrame> pendingQuestion() {
        List<TaskFrame> stack = taskStack();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        TaskFrame top = stack.get(stack.size() - 1);
        return top.isOpen() && top.isEphemeral() ? Optional.of(top) : Optional.empty();
    }

    public Optional<RouteDecision> route() {
        return value(ROUTE);
    }

    public String nextNode() {
        return this.<String>value(NEXT_NODE).orElse(FlowNames.END_TURN);
    }

    public List<String> replies() {
        return this.<List<String>>value(REPLIES).orElse(List.of());
    }
}

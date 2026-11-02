package com.example.smartflow.memory;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.HistoryMessage;
import com.example.smartflow.flow.MemoryFact;
import com.example.smartflow.flow.ToolFact;
import com.example.smartflow.node.HistoryPolicy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按节点策略选取历史窗口，组装历史消息、摘要、长期记忆和工具结果。 */
public final class NodeContextBuilder {

    /** 每条历史工具结果的字符上限，避免大段数据占满上下文。 */
    private static final int TOOL_RESULT_MAX_CHARS = 500;

    private final FlowProperties props;
    private final TokenEstimator tokenEstimator;

    public NodeContextBuilder(FlowProperties props, TokenEstimator tokenEstimator) {
        this.props = props;
        this.tokenEstimator = tokenEstimator;
    }

    public HistoryPolicy defaultPolicy() {
        var m = props.memory();
        return new HistoryPolicy(m.recentMessages(), m.maxHistoryTokens(), true, true, false);
    }

    /** 至少保留最近一条消息，其余同时受条数和 token 预算约束。 */
    public List<HistoryMessage> window(FlowState state, int limit, HistoryPolicy policy) {
        List<HistoryMessage> history = state.history();
        int budget = policy.maxTokens();
        int used = 0;
        int from = history.size();
        while (from > 0 && history.size() - from < limit) {
            int cost = tokenEstimator.estimate(history.get(from - 1).content());
            if (from < history.size() && used + cost > budget) {
                break;
            }
            used += cost;
            from--;
        }
        return history.subList(from, history.size());
    }

    public List<ChatMessage> toChatMessages(List<HistoryMessage> messages) {
        List<ChatMessage> out = new ArrayList<>();
        for (HistoryMessage m : messages) {
            out.add(m.isUser() ? UserMessage.from(m.content()) : AiMessage.from(m.content()));
        }
        return out;
    }

    public String historyAsText(List<HistoryMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (HistoryMessage m : messages) {
            sb.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        return sb.isEmpty() ? "（无）" : sb.toString();
    }

    /** window 由节点选定，保证工具结果与节点实际使用的历史窗口一致。 */
    public String memoryBlock(FlowState state, HistoryPolicy p, List<HistoryMessage> window) {
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
            for (HistoryMessage m : window) {
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
}

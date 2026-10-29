package com.example.smartflow.node;

/**
 * 节点要带多少记忆。每个节点通过覆盖 {@link AbstractNode#historyPolicy()} 声明，默认值来自 flow.memory 配置。
 *
 * @param maxMessages        最多带多少条历史消息
 * @param maxTokens          历史消息的 token 上限（估算），和条数限制同时生效，从最新的往前保留
 * @param includeSummary     是否带上早期对话摘要
 * @param includeUserMemory  是否带上用户长期记忆
 * @param includeToolResults 是否带上历史窗口里的工具调用结果（"最近查询到的数据"），查询类节点打开它，
 *                           用户追问"那明天呢"时模型能看到上一轮查到的原始数据
 */
public record HistoryPolicy(int maxMessages,
                            int maxTokens,
                            boolean includeSummary,
                            boolean includeUserMemory,
                            boolean includeToolResults) {

    public HistoryPolicy withMaxMessages(int n) {
        return new HistoryPolicy(n, maxTokens, includeSummary, includeUserMemory, includeToolResults);
    }

    public HistoryPolicy withMaxTokens(int n) {
        return new HistoryPolicy(maxMessages, n, includeSummary, includeUserMemory, includeToolResults);
    }

    public HistoryPolicy withSummary(boolean b) {
        return new HistoryPolicy(maxMessages, maxTokens, b, includeUserMemory, includeToolResults);
    }

    public HistoryPolicy withUserMemory(boolean b) {
        return new HistoryPolicy(maxMessages, maxTokens, includeSummary, b, includeToolResults);
    }

    public HistoryPolicy withToolResults(boolean b) {
        return new HistoryPolicy(maxMessages, maxTokens, includeSummary, includeUserMemory, b);
    }
}

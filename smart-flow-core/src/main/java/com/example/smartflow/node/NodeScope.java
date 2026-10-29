package com.example.smartflow.node;

import com.example.smartflow.engine.TurnContext;

/**
 * 一次节点执行的上下文：所属轮次、本次执行 ID。传给 AbstractNode 的各个辅助方法。
 */
public record NodeScope(TurnContext turn, String execId, String nodeId) {
}

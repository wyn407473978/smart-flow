package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 路由结果。action / intent 由模型给出，target 由代码根据子节点的路由声明计算。
 *
 * @param action   动作
 * @param intent   意图编码，对应某个子节点声明的 RouteSpec.intent
 * @param target   最终要去的节点（代码计算）
 * @param reason   模型给出的理由，便于排查
 * @param fallback 是否因为模型输出异常或意图不认识走了兜底
 */
public record RouteDecision(Action action,
                            String intent,
                            String target,
                            String reason,
                            boolean fallback) implements Serializable {

    public enum Action {
        /** 继续当前任务（用户在回答当前任务的问题） */
        CONTINUE,
        /** 开始一个新任务或新话题 */
        START,
        /** 当前有任务，用户临时问了别的事情，当前任务保留 */
        DIGRESS,
        /** 取消当前任务 */
        CANCEL
    }
}

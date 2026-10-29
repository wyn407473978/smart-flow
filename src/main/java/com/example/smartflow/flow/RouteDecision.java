package com.example.smartflow.flow;

import java.io.Serializable;

/**
 * 路由结果。action / intent 由模型给出，target 由代码根据规则计算。
 *
 * @param action   模型判断的动作
 * @param intent   模型判断的意图
 * @param target   最终要去的节点（代码计算）
 * @param reason   模型给出的理由，便于排查
 * @param fallback 是否因为模型输出异常走了兜底逻辑
 */
public record RouteDecision(Action action,
                            Intent intent,
                            String target,
                            String reason,
                            boolean fallback) implements Serializable {

    public enum Action {
        /** 继续当前任务（用户在回答当前任务的问题） */
        CONTINUE,
        /** 开始一个新任务 */
        START,
        /** 临时插入别的话题，当前任务保留 */
        DIGRESS,
        /** 取消当前任务 */
        CANCEL
    }

    public enum Intent {
        ACTIVITY_CREATE,
        BIZ_QUERY,
        CHAT
    }

    public RouteDecision withTarget(String newTarget) {
        return new RouteDecision(action, intent, newTarget, reason, fallback);
    }
}

package com.example.smartflow.demo;

/**
 * 智慧民生示例工作流的节点 ID、任务类型。
 */
public final class MinshengNames {

    private MinshengNames() {
    }

    // ---- 节点 ----
    /** B2：业务查询（挂载工具） */
    public static final String BIZ_QUERY = "biz_query";
    /** B3：自由对话，根路由的兜底节点 */
    public static final String CHAT = "chat";
    /** C1：活动创建 - 收集目标人群 */
    public static final String COLLECT_TARGET = "collect_target";
    /** C2：活动创建 - 模版确认 */
    public static final String CHOOSE_TEMPLATE = "choose_template";

    // ---- 任务类型 ----
    public static final String TASK_ACTIVITY_CREATE = "ACTIVITY_CREATE";
}

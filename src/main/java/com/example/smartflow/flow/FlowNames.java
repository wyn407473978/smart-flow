package com.example.smartflow.flow;

/**
 * 节点 ID、任务类型等常量。节点 ID 同时也是图里的节点名、日志里的 node_id。
 */
public final class FlowNames {

    private FlowNames() {
    }

    // ---- 节点 ----
    /** A：全局路由，每轮必经 */
    public static final String ROUTER = "router";
    /** B2：业务查询（挂载工具） */
    public static final String BIZ_QUERY = "biz_query";
    /** B3：自由对话 */
    public static final String CHAT = "chat";
    /** C1：活动创建 - 收集目标人群 */
    public static final String COLLECT_TARGET = "collect_target";
    /** C2：活动创建 - 模版确认（最小闭环里是占位节点） */
    public static final String CHOOSE_TEMPLATE = "choose_template";
    /** 路由直接结束本轮（例如取消任务，回复已由路由节点给出） */
    public static final String END_TURN = "end_turn";

    // ---- 任务类型 ----
    public static final String TASK_ACTIVITY_CREATE = "ACTIVITY_CREATE";
}

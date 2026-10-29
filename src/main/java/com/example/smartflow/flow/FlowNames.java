package com.example.smartflow.flow;

/**
 * 框架内置的节点 ID。节点 ID 同时也是图里的节点名、日志里的 node_id。
 * <p>
 * 业务节点的 ID、任务类型请定义在业务自己的常量类里（参考 demo 里的 MinshengNames）。
 */
public final class FlowNames {

    private FlowNames() {
    }

    /** 根路由节点，每轮必经。业务子节点的 RouteSpec.parent 一般填它 */
    public static final String ROUTER = "router";
    /** 结束本轮（例如取消任务，回复已由路由节点给出） */
    public static final String END_TURN = "end_turn";
}

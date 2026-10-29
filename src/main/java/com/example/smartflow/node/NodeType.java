package com.example.smartflow.node;

public enum NodeType {
    /** 意图识别 / 分发（A、B1） */
    ROUTER,
    /** 多轮收集参数（C1） */
    SLOT_FILLING,
    /** 挂载工具、ReAct 循环（B2、C2） */
    TOOL_AGENT,
    /** 二次确认后由代码执行写操作（C3） */
    CONFIRM,
    /** 自由对话（B3） */
    CHAT,
    /** 尚未实现的占位节点 */
    PLACEHOLDER
}

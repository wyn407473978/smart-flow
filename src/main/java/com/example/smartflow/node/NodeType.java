package com.example.smartflow.node;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 节点类型，只用于记录（flow_node_exec.node_type）和调试页面展示，不参与流转逻辑。
 * <p>
 * 内置了几种通用类型；业务工作流需要新类型时直接 {@code NodeType.of("APPROVAL")}，不用改框架代码。
 *
 * @param name 类型编码，大写字母、数字、下划线
 */
public record NodeType(String name) {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** 意图识别 / 分发 */
    public static final NodeType ROUTER = new NodeType("ROUTER");
    /** 多轮收集参数 */
    public static final NodeType SLOT_FILLING = new NodeType("SLOT_FILLING");
    /** 挂载工具、ReAct 循环 */
    public static final NodeType TOOL_AGENT = new NodeType("TOOL_AGENT");
    /** 二次确认后由代码执行写操作 */
    public static final NodeType CONFIRM = new NodeType("CONFIRM");
    /** 自由对话 */
    public static final NodeType CHAT = new NodeType("CHAT");
    /** 尚未实现的占位节点 */
    public static final NodeType PLACEHOLDER = new NodeType("PLACEHOLDER");

    public NodeType {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("节点类型只能由大写字母、数字、下划线组成，且以字母开头: " + name);
        }
    }

    public static NodeType of(String name) {
        return new NodeType(name);
    }

    @Override
    public String toString() {
        return name;
    }
}

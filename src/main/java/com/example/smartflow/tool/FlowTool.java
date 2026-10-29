package com.example.smartflow.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Map;

/**
 * 工作流里的一个工具。
 * <p>
 * 没有用 LangChain4j 的 @Tool 注解 + AiServices，而是显式给出 {@link ToolSpecification}，
 * 由节点自己跑 tool loop，这样每次工具调用的参数、结果、耗时都能完整落库，测试时也方便替换。
 */
public interface FlowTool {

    /** 告诉模型这个工具叫什么、做什么、需要什么参数 */
    ToolSpecification specification();

    default String name() {
        return specification().name();
    }

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数（已从 JSON 解析成 Map）
     * @param context   调用上下文（会话、轮次），查询"我的信息"这类工具需要知道是谁在问
     * @return 返回给模型的结果，建议是 JSON 字符串
     */
    String execute(Map<String, Object> arguments, ToolContext context) throws Exception;

    record ToolContext(String sessionId, String turnId) {
    }
}

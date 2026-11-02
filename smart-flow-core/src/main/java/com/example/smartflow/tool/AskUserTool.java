package com.example.smartflow.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * 内置工具：模型需要用户补充或确认信息时调用它，而不是直接在回复里提问。
 * <p>
 * 工具本身只返回用于落库的结果；结束本轮、记录等待状态和恢复节点由工作流负责。
 */
public final class AskUserTool implements FlowTool {

    public static final String NAME = "ask_user";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("需要用户补充或确认信息才能继续时调用（例如查天气不知道城市、需要用户确认是不是某个城市）。"
                    + "question 是要对用户说的话。调用后本轮结束，等用户回答。"
                    + "调用这个工具时不要再输出其他文字，也不要在普通回复里向用户提问。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("question", "要问用户的话，简短、口语化")
                    .required("question")
                    .build())
            .build();

    public static final AskUserTool INSTANCE = new AskUserTool();

    private AskUserTool() {
    }

    @Override
    public ToolSpecification specification() {
        return SPEC;
    }

    /** 只用来让 ask_user 和普通工具走同一套落库逻辑 */
    @Override
    public String execute(Map<String, Object> arguments, ToolContext context) {
        return "{\"status\":\"已向用户提问，等待回答\"}";
    }
}

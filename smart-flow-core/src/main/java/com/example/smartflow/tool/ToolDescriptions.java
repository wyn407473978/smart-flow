package com.example.smartflow.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Optional;

/** 工具定义的调试展示文本，包含自动挂载的内置工具。 */
public final class ToolDescriptions {

    private ToolDescriptions() {
    }

    public static String describe(List<FlowTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (FlowTool tool : tools) {
            ToolSpecification spec = tool.specification();
            sb.append(spec.name()).append("：").append(spec.description()).append('\n');
            JsonObjectSchema params = spec.parameters();
            if (params == null || params.properties() == null || params.properties().isEmpty()) {
                sb.append("  参数：无\n");
            } else {
                List<String> required = params.required() == null ? List.of() : params.required();
                params.properties().forEach((name, schema) -> sb.append("  - ").append(name)
                        .append(required.contains(name) ? "（必填）" : "（可选）")
                        .append("：").append(Optional.ofNullable(schema.description()).orElse("")).append('\n'));
            }
            sb.append('\n');
        }
        sb.append(AskUserTool.NAME).append("（内置，streamWithTools 自动挂载）：")
                .append(AskUserTool.INSTANCE.specification().description());
        return sb.toString().stripTrailing();
    }
}

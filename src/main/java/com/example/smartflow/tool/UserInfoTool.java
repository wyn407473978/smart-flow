package com.example.smartflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询当前用户的个人信息。演示用：固定返回 姓名 rise、年龄 27。
 * 接真实用户系统时，用 {@link ToolContext#sessionId()} 找到登录用户再查。
 */
@Component
public class UserInfoTool implements FlowTool {

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name("get_user_info")
            .description("查询当前登录用户本人的个人信息，包括姓名、年龄。用户问\"我叫什么\"\"我多大了\"\"我的个人信息\"时使用")
            // 不需要参数：查的永远是当前用户本人，不允许模型传别人的 ID
            .parameters(JsonObjectSchema.builder().build())
            .build();

    private final ObjectMapper mapper;

    public UserInfoTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolSpecification specification() {
        return SPEC;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext context) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "rise");
        result.put("age", 27);
        result.put("source", "演示数据");
        return mapper.writeValueAsString(result);
    }
}

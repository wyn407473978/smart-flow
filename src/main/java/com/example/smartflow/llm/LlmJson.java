package com.example.smartflow.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 解析模型输出的 JSON 用的公共工具：宽松的 ObjectMapper + 去掉 ```json 代码块包裹。
 */
public final class LlmJson {

    private LlmJson() {
    }

    /** 枚举大小写不敏感、多余字段忽略、未知枚举值当 null */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build();

    /** 模型经常把 JSON 包在 ```json ... ``` 里，去掉它 */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return t;
    }
}

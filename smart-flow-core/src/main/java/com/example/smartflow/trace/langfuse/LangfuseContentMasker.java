package com.example.smartflow.trace.langfuse;

import com.fasterxml.jackson.databind.JsonNode;

/** 在外发前处理内容。业务工程可提供自己的 Bean，覆盖默认脱敏策略。 */
@FunctionalInterface
public interface LangfuseContentMasker {
    JsonNode mask(JsonNode content);
}

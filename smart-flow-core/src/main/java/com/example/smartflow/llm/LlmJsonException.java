package com.example.smartflow.llm;

/** 模型多次输出非法 JSON。 */
public class LlmJsonException extends RuntimeException {

    private final String lastOutput;

    public LlmJsonException(String purpose, String lastOutput, Throwable cause) {
        super("模型输出无法解析为 JSON, purpose=" + purpose + ", output=" + lastOutput, cause);
        this.lastOutput = lastOutput;
    }

    public String lastOutput() {
        return lastOutput;
    }
}

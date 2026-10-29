package com.example.smartflow.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 记录每一次模型调用的真实输入输出。
 * <p>
 * onRequest 拿到的是 LangChain4j 合并了模型默认参数之后的最终请求（模型名、温度等都已经确定），
 * 比在业务代码里记录自己拼的请求更接近"真实发出去的内容"。
 * 如果还需要原始 HTTP 报文，把配置 flow.model.log-http 打开。
 */
@Component
public class TraceChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TraceChatModelListener.class);

    private final TraceRepository repo;
    private final ObjectMapper mapper;

    public TraceChatModelListener(TraceRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        Map<Object, Object> attrs = ctx.attributes();
        Object preset = attrs.get(TraceKeys.CALL_ID);
        String callId = preset != null ? preset.toString() : UUID.randomUUID().toString();
        attrs.put(TraceKeys.CALL_ID, callId);
        attrs.put(TraceKeys.START_NANOS, System.nanoTime());
        try {
            ChatRequest request = ctx.chatRequest();
            repo.insertLlmCall(
                    callId,
                    str(attrs.get(TraceKeys.TURN_ID)),
                    str(attrs.get(TraceKeys.SESSION_ID)),
                    str(attrs.get(TraceKeys.EXEC_ID)),
                    str(attrs.get(TraceKeys.NODE_ID)),
                    str(attrs.get(TraceKeys.PURPOSE)),
                    ctx.modelProvider() == null ? null : ctx.modelProvider().name(),
                    request.parameters() == null ? null : request.parameters().modelName(),
                    Boolean.TRUE.equals(attrs.get(TraceKeys.STREAMING)),
                    mapper.writeValueAsString(requestToJson(request)));
        } catch (Exception e) {
            log.warn("记录模型请求失败 callId={}", callId, e);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Map<Object, Object> attrs = ctx.attributes();
        String callId = str(attrs.get(TraceKeys.CALL_ID));
        if (callId == null) {
            return;
        }
        try {
            ChatResponse response = ctx.chatResponse();
            TokenUsage usage = response.tokenUsage();
            AiMessage ai = response.aiMessage();
            repo.finishLlmCall(
                    callId,
                    mapper.writeValueAsString(responseToJson(response)),
                    ai == null ? null : ai.text(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(),
                    response.finishReason() == null ? null : response.finishReason().name(),
                    "SUCCESS",
                    null,
                    latencyMs(attrs));
        } catch (Exception e) {
            log.warn("记录模型响应失败 callId={}", callId, e);
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Map<Object, Object> attrs = ctx.attributes();
        String callId = str(attrs.get(TraceKeys.CALL_ID));
        if (callId == null) {
            return;
        }
        try {
            Throwable err = ctx.error();
            repo.finishLlmCall(callId, null, null, null, null, null, null,
                    "ERROR", err == null ? null : err.getClass().getSimpleName() + ": " + err.getMessage(),
                    latencyMs(attrs));
        } catch (Exception e) {
            log.warn("记录模型异常失败 callId={}", callId, e);
        }
    }

    // ------------------------------------------------------------------ json

    private ObjectNode requestToJson(ChatRequest request) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ChatRequestParameters p = request.parameters();
        ObjectNode params = root.putObject("parameters");
        if (p != null) {
            params.put("modelName", p.modelName());
            params.put("temperature", p.temperature());
            params.put("topP", p.topP());
            params.put("maxOutputTokens", p.maxOutputTokens());
            params.put("responseFormat", p.responseFormat() == null ? null : p.responseFormat().type().name());
            if (p.toolSpecifications() != null && !p.toolSpecifications().isEmpty()) {
                ArrayNode tools = params.putArray("tools");
                for (ToolSpecification spec : p.toolSpecifications()) {
                    ObjectNode t = tools.addObject();
                    t.put("name", spec.name());
                    t.put("description", spec.description());
                }
            }
        }
        root.set("messages", mapper.readTree(ChatMessageSerializer.messagesToJson(request.messages())));
        return root;
    }

    private ObjectNode responseToJson(ChatResponse response) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.id());
        root.put("modelName", response.modelName());
        root.put("finishReason", response.finishReason() == null ? null : response.finishReason().name());
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            ObjectNode u = root.putObject("tokenUsage");
            u.put("input", usage.inputTokenCount());
            u.put("output", usage.outputTokenCount());
            u.put("total", usage.totalTokenCount());
        }
        if (response.aiMessage() != null) {
            JsonNode ai = mapper.readTree(ChatMessageSerializer.messageToJson(response.aiMessage()));
            root.set("aiMessage", ai);
        }
        return root;
    }

    private static long latencyMs(Map<Object, Object> attrs) {
        Object start = attrs.get(TraceKeys.START_NANOS);
        return start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : -1;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

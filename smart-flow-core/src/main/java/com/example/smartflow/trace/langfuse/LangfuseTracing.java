package com.example.smartflow.trace.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 复用本地 trace 的生命周期。通过显式 ID 关联上下文，不依赖请求线程的 ThreadLocal，
 * 因此模型的流式回调、图执行线程和本轮结束后的后台记忆任务都能关联到同一 trace。
 */
public final class LangfuseTracing implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LangfuseTracing.class);
    private record TraceContext(Context context, String turnId, String sessionId, String userId) { }
    private final SdkTracerProvider provider;
    private final Tracer tracer;
    private final LangfuseProperties properties;
    private final ObjectMapper mapper;
    private final LangfuseContentMasker masker;
    // 只保留轻量上下文，不缓存提示词、回复或个人数据。结束后的上下文用于异步记忆任务。
    private final Cache<String, TraceContext> turns = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, Span> active = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofMinutes(15))
            .<String, Span>evictionListener((id, span, cause) -> {
                if (span != null) {
                    span.setStatus(StatusCode.ERROR, "Observation expired before completion");
                    span.end();
                }
            }).build();

    public LangfuseTracing(SdkTracerProvider provider, LangfuseProperties properties,
                           ObjectMapper mapper, LangfuseContentMasker masker) {
        this.provider = provider;
        this.tracer = provider.get("com.example.smartflow.langfuse", "1.0");
        this.properties = properties;
        this.mapper = mapper;
        this.masker = masker;
    }

    public void startTurn(String turnId, String sessionId, String userId, String input) {
        safely(() -> {
            Span root = tracer.spanBuilder("handle-chat-turn").setNoParent().startSpan();
            TraceContext context = new TraceContext(Context.root().with(root), turnId,
                    pseudonym(sessionId), pseudonym(userId));
            turns.put(turnId, context);
            put("turn:" + turnId, root);
            common(root, context, "chain");
            content(root, "input", input);
        });
    }

    public void startNode(String execId, String turnId, String nodeId, String nodeType, Object input) {
        safely(() -> {
            Span span = start("execute-" + nodeId, turnId, null,
                    "TOOL_AGENT".equals(nodeType) ? "agent" : "chain");
            put("node:" + execId, span);
            span.setAttribute("langfuse.observation.metadata.node_id", nodeId);
            span.setAttribute("langfuse.observation.metadata.node_type", nodeType);
            content(span, "input", input);
        });
    }

    public void identifyUser(String turnId, String userId) {
        safely(() -> {
            TraceContext current = turns.getIfPresent(turnId);
            if (current == null || userId == null) return;
            String pseudonym = pseudonym(userId);
            turns.put(turnId, new TraceContext(current.context(), turnId, current.sessionId(), pseudonym));
            Span root = active.getIfPresent("turn:" + turnId);
            if (root != null) attr(root, "langfuse.user.id", pseudonym);
        });
    }

    public void startGeneration(String callId, String turnId, String sessionId, String execId,
                                String nodeId, String purpose, String providerName, String model,
                                boolean streaming, String requestJson) {
        safely(() -> {
            String operation = purpose == null ? "response" : purpose.replaceFirst("#\\d+$", "");
            Span span = start("generate-" + operation, turnId, execId, "generation");
            put("llm:" + callId, span);
            // 仍保留没有 turn 的独立调用的会话关联。
            if (turns.getIfPresent(turnId == null ? "" : turnId) == null && sessionId != null) {
                span.setAttribute("langfuse.session.id", pseudonym(sessionId));
            }
            attr(span, "langfuse.observation.model.name", model);
            attr(span, "gen_ai.provider.name", providerName);
            attr(span, "langfuse.observation.metadata.node_id", nodeId);
            attr(span, "langfuse.observation.metadata.purpose", purpose);
            span.setAttribute("langfuse.observation.metadata.call_id", callId);
            span.setAttribute("langfuse.observation.metadata.streaming", streaming);
            if (requestJson != null) {
                JsonNode request = mapper.readTree(requestJson);
                // 参数本身没有正文，但工具描述/定义仍走同一内容开关和脱敏管道。
                ObjectNode parameters = request.path("parameters").isObject()
                        ? (ObjectNode) request.path("parameters").deepCopy() : mapper.createObjectNode();
                JsonNode tools = parameters.remove("tools");
                span.setAttribute("langfuse.observation.model.parameters", parameters.toString());
                content(span, "input", messages(request.path("messages")));
                if (properties.captureContent() && tools != null) {
                    span.setAttribute("langfuse.observation.metadata.tools", encoded(tools));
                }
            }
        });
    }

    public void endGeneration(String callId, String responseJson, String responseText,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens,
                              String finishReason, String status, String error) {
        finish("llm:" + callId, status, error, span -> {
            ObjectNode usage = mapper.createObjectNode();
            if (inputTokens != null) usage.put("input", inputTokens);
            if (outputTokens != null) usage.put("output", outputTokens);
            if (totalTokens != null) usage.put("total", totalTokens);
            span.setAttribute("langfuse.observation.usage_details", usage.toString());
            attr(span, "langfuse.observation.metadata.finish_reason", finishReason);
            if (responseJson != null) {
                try {
                    JsonNode response = mapper.readTree(responseJson);
                    attr(span, "langfuse.observation.model.name", response.path("modelName").asText(null));
                    ArrayNode output = mapper.createArrayNode();
                    output.add(message(response.path("aiMessage")));
                    content(span, "output", output);
                } catch (Exception ignored) {
                    content(span, "output", responseText);
                }
            } else {
                content(span, "output", responseText);
            }
        });
    }

    public void startTool(String toolCallId, String turnId, String execId, String llmCallId,
                          String toolName, String arguments) {
        safely(() -> {
            // 工具与请求它的 generation 是 agent 下的兄弟节点，而不是 generation 的子节点。
            Span span = start("execute-" + toolName, turnId, execId, "tool");
            put("tool:" + toolCallId, span);
            attr(span, "langfuse.observation.metadata.requesting_call_id", llmCallId);
            content(span, "input", parse(arguments));
        });
    }

    public void endTurn(String turnId, String output, String status, String error) {
        finish("turn:" + turnId, status, error, span -> content(span, "output", output));
    }

    public void endNode(String execId, Object output, String status, String error) {
        finish("node:" + execId, status, error, span -> content(span, "output", output));
    }

    public void endTool(String toolCallId, String output, String status, String error) {
        finish("tool:" + toolCallId, status, error, span -> content(span, "output", parse(output)));
    }

    private Span start(String name, String turnId, String execId, String type) {
        TraceContext context = turnId == null ? null : turns.getIfPresent(turnId);
        Span node = execId == null ? null : active.getIfPresent("node:" + execId);
        Context parent = node != null ? Context.root().with(node)
                : context != null ? context.context() : Context.root();
        Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
        common(span, context, type);
        return span;
    }

    private void common(Span span, TraceContext context, String type) {
        span.setAttribute("langfuse.observation.type", type);
        span.setAttribute("langfuse.trace.name", "handle-chat-turn");
        span.setAttribute("langfuse.environment", properties.environment());
        span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), List.of("smart-flow"));
        attr(span, "langfuse.release", properties.release());
        if (context != null) {
            attr(span, "langfuse.session.id", context.sessionId());
            attr(span, "langfuse.user.id", context.userId());
            attr(span, "langfuse.trace.metadata.turn_id", context.turnId());
        }
    }

    private void put(String key, Span span) {
        Span previous = active.asMap().put(key, span);
        if (previous != null) previous.end();
    }

    private void finish(String key, String status, String error, Consumer<Span> update) {
        safely(() -> {
            Span span = active.asMap().remove(key);
            if (span == null) return;
            try {
                if (!"SUCCESS".equals(status)) {
                    span.setStatus(StatusCode.ERROR, "Operation failed");
                    span.setAttribute("langfuse.observation.level", "ERROR");
                    if (properties.captureContent() && error != null) {
                        span.setAttribute("langfuse.observation.status_message", encoded(error));
                    }
                }
                update.accept(span);
            } finally {
                span.end();
            }
        });
    }

    private void content(Span span, String field, Object value) {
        if (!properties.captureContent()) {
            span.setAttribute("langfuse.observation." + field, "\"[Content capture disabled]\"");
        } else if (value != null) {
            span.setAttribute("langfuse.observation." + field, encoded(value));
        }
    }

    private String encoded(Object value) {
        JsonNode json = value instanceof JsonNode node ? node.deepCopy() : mapper.valueToTree(value);
        String result = masker.mask(json).toString();
        if (result.length() > properties.maxContentChars()) {
            // 保持截断后的值仍为合法 JSON；先脱敏再截断。
            return mapper.getNodeFactory().textNode(result.substring(0, properties.maxContentChars() / 2)
                    + "…[truncated]").toString();
        }
        return result;
    }

    private JsonNode parse(String value) {
        if (value == null) return mapper.nullNode();
        try { return mapper.readTree(value); }
        catch (Exception ignored) { return mapper.getNodeFactory().textNode(value); }
    }

    private ArrayNode messages(JsonNode source) {
        ArrayNode result = mapper.createArrayNode();
        if (source.isArray()) source.forEach(item -> result.add(message(item)));
        return result;
    }

    /** LangChain4j 的序列化格式转换为 Langfuse 可读的 OpenAI 消息格式。 */
    private ObjectNode message(JsonNode source) {
        ObjectNode result = mapper.createObjectNode();
        String type = source.path("type").asText("").toUpperCase(Locale.ROOT);
        result.put("role", switch (type) {
            case "SYSTEM" -> "system";
            case "AI" -> "assistant";
            case "TOOL_EXECUTION_RESULT" -> "tool";
            default -> "user";
        });
        if (source.has("text")) result.set("content", source.get("text"));
        else if (source.has("contents")) {
            ArrayNode parts = mapper.createArrayNode();
            source.path("contents").forEach(part -> {
                if (part.has("text")) parts.addObject().put("type", "text").set("text", part.get("text"));
            });
            result.set("content", parts);
        }
        if (source.hasNonNull("thinking")) result.set("reasoning_content", source.get("thinking"));
        if (source.hasNonNull("toolExecutionRequests")) {
            ArrayNode calls = result.putArray("tool_calls");
            source.path("toolExecutionRequests").forEach(call -> {
                ObjectNode item = calls.addObject().put("type", "function");
                item.set("id", call.path("id"));
                ObjectNode function = item.putObject("function");
                function.set("name", call.path("name"));
                function.put("arguments", call.path("arguments").asText("{}"));
            });
        }
        if ("TOOL_EXECUTION_RESULT".equals(type)) result.set("tool_call_id", source.path("id"));
        return result;
    }

    private String pseudonym(String value) throws Exception {
        if (value == null || value.isBlank()) return null;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void attr(Span span, String key, String value) {
        if (value != null && !value.isBlank()) span.setAttribute(key, value);
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }

    private void safely(Action action) {
        try { action.run(); }
        catch (Exception e) {
            // 外部遥测失败不能打断对话，也不能把异常中可能包含的正文或认证信息写到日志。
            log.warn("Langfuse instrumentation failed ({})", e.getClass().getSimpleName());
        }
    }

    public void flush() {
        provider.forceFlush().join(10, TimeUnit.SECONDS);
    }

    /** 与本地 turnId 对照查找 Langfuse trace，包含已结束但仍有后台记忆任务的轮次。 */
    public String traceId(String turnId) {
        TraceContext context = turns.getIfPresent(turnId);
        return context == null ? null : Span.fromContext(context.context()).getSpanContext().getTraceId();
    }

    @Override
    public void close() {
        active.asMap().forEach((key, span) -> span.end());
        active.invalidateAll();
        turns.invalidateAll();
        provider.shutdown().join(10, TimeUnit.SECONDS);
    }
}

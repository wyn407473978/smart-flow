package com.example.smartflow.trace.langfuse;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.config.ModelConfig;
import com.example.smartflow.engine.TurnContext;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.tool.FlowTool;
import com.example.smartflow.trace.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LangfuseTracingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toolLoopAndBackgroundMemoryProduceAuditableHierarchyAcrossThreads() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var tracing = new LangfuseTracing(provider, properties(true), JSON, new DefaultLangfuseContentMasker())) {
            String turnId = exerciseApplication(tracing);
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(6);
            SpanData root = named(spans, "handle-chat-turn");
            SpanData agent = named(spans, "execute-biz_query");
            SpanData tool = named(spans, "execute-get_weather");
            SpanData memory = named(spans, "generate-extract_memory");
            assertThat(agent.getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(tool.getParentSpanId()).isEqualTo(agent.getSpanId());
            assertThat(memory.getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(attr(agent, "langfuse.observation.type")).isEqualTo("agent");
            assertThat(attr(tool, "langfuse.observation.type")).isEqualTo("tool");
            assertThat(attr(root, "langfuse.observation.input")).isEqualTo("\"今天杭州天气怎么样\"");
            assertThat(attr(root, "langfuse.observation.output")).contains("演示天气：晴");
            List<SpanData> generations = spans.stream().filter(s -> s.getName().equals("generate-answer")).toList();
            assertThat(generations).hasSize(2).allSatisfy(generation -> {
                assertThat(generation.getParentSpanId()).isEqualTo(agent.getSpanId());
                assertThat(attr(generation, "langfuse.observation.type")).isEqualTo("generation");
                assertThat(attr(generation, "langfuse.observation.model.name")).isEqualTo("test-model");
                assertThat(attr(generation, "langfuse.observation.usage_details")).contains("\"input\":20", "\"output\":5");
            });
            JsonNode firstOutput = JSON.readTree(attr(generations.getFirst(), "langfuse.observation.output"));
            assertThat(firstOutput.at("/0/role").asText()).isEqualTo("assistant");
            assertThat(firstOutput.at("/0/tool_calls/0/function/name").asText()).isEqualTo("get_weather");
            assertThat(firstOutput.at("/0/tool_calls/0/function/arguments").asText()).contains("杭州");
            JsonNode secondInput = JSON.readTree(attr(generations.getLast(), "langfuse.observation.input"));
            assertThat(secondInput.get(secondInput.size() - 1).path("role").asText()).isEqualTo("tool");
            assertThat(secondInput.get(secondInput.size() - 1).path("tool_call_id").asText()).isEqualTo("call-weather");
            assertThat(attr(generations.getLast(), "langfuse.observation.input")).doesNotContain("13800138000", "alice@example.com");
            assertThat(tracing.traceId(turnId)).isEqualTo(root.getTraceId());
            assertThat(spans).allSatisfy(span -> {
                assertThat(span.getTraceId()).isEqualTo(root.getTraceId());
                assertThat(attr(span, "langfuse.session.id")).isEqualTo(attr(root, "langfuse.session.id"));
                assertThat(attr(span, "langfuse.user.id")).isEqualTo(attr(root, "langfuse.user.id")).isNotEqualTo("synthetic-user");
                assertThat(attr(span, "langfuse.trace.metadata.turn_id")).isEqualTo(turnId);
                assertThat(attr(span, "langfuse.environment")).isEqualTo("test");
            });
        }
    }

    @Test
    void contentIsExcludedByDefaultIncludingErrorMessagesAndPersonalToolResults() {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var tracing = new LangfuseTracing(provider, properties(false), JSON, new DefaultLangfuseContentMasker())) {
            tracing.startTurn("turn", "alice@example.com", "13800138000", "private prompt");
            tracing.startTool("tool", "turn", null, "call", "get_user_info", "{\"name\":\"Alice\"}");
            tracing.endTool("tool", "{\"name\":\"Alice\"}", "ERROR", "password=secret-value");
            tracing.endTurn("turn", "private reply", "SUCCESS", null);
            assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> {
                String attributes = span.getAttributes().toString();
                assertThat(attributes).doesNotContain("Alice", "alice@example.com", "13800138000", "private prompt", "private reply", "secret-value");
                assertThat(attr(span, "langfuse.observation.input")).contains("Content capture disabled");
            });
            SpanData tool = named(exporter.getFinishedSpanItems(), "execute-get_user_info");
            assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        }
    }

    @Test
    void maskerHandlesJsonEncodedToolResultsAndDoesNotDestroyToolNames() throws Exception {
        var masker = new DefaultLangfuseContentMasker();
        JsonNode masked = masker.mask(JSON.readTree("""
                {"function":{"name":"get_user_info","arguments":"{\\"email\\":\\"alice@example.com\\"}"},
                 "content":"{\\"name\\":\\"Alice\\",\\"age\\":36,\\"phone\\":\\"13800138000\\"}"}
                """));
        assertThat(masked.at("/function/name").asText()).isEqualTo("get_user_info");
        assertThat(masked.toString()).doesNotContain("alice@example.com", "Alice", "13800138000", "36");
    }

    @Test
    void maskingFailureDoesNotBreakApplicationOrLoseCompletedSpans() {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var tracing = new LangfuseTracing(provider, properties(true), JSON, value -> { throw new IllegalStateException(); })) {
            tracing.startTurn("turn", "session", null, "prompt");
            tracing.endTurn("turn", "reply", "SUCCESS", null);
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        }
    }

    @Test
    void samplingDecisionAppliesToTheWholeTurnIncludingBackgroundWork() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOff()))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var tracing = new LangfuseTracing(provider, properties(true), JSON, new DefaultLangfuseContentMasker())) {
            exerciseApplication(tracing);
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
    }

    @Test
    void disabledIntegrationCreatesNoExporterAndRequiresNoCredentials() {
        new ApplicationContextRunner().withUserConfiguration(LangfuseConfiguration.class)
                .withBean(ObjectMapper.class, () -> JSON)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LangfuseTracing.class));
    }

    @Test
    void enabledIntegrationRequiresCredentialsWithoutEchoingSecrets() {
        new ApplicationContextRunner().withUserConfiguration(LangfuseConfiguration.class)
                .withBean(ObjectMapper.class, () -> JSON)
                .withPropertyValues("flow.langfuse.enabled=true", "flow.langfuse.secret-key=do-not-echo-this")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("LANGFUSE_BASE_URL")
                            .hasStackTraceContaining("LANGFUSE_PUBLIC_KEY");
                });
        assertThat(properties(true).toString()).doesNotContain("sk-lf-test-secret", "pk-lf-test-public");
    }

    @Test
    void realOtlpHttpExporterUsesAuthenticatedV4EndpointAndFlushesBatches() throws Exception {
        List<String> authorization = new CopyOnWriteArrayList<>();
        List<String> versions = new CopyOnWriteArrayList<>();
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            versions.add(exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"));
            bodies.add(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            var properties = new LangfuseProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "pk-lf-test-public", "sk-lf-test-secret", false, "test", "test-release", 1.0, null);
            try (var tracing = new LangfuseConfiguration().langfuseTracing(properties, JSON, new DefaultLangfuseContentMasker())) {
                exerciseApplication(tracing);
                tracing.flush();
                assertThat(bodies).isNotEmpty().allSatisfy(body -> assertThat(body).isNotEmpty());
                assertThat(versions).containsOnly("4");
                String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "pk-lf-test-public:sk-lf-test-secret".getBytes(StandardCharsets.UTF_8));
                assertThat(authorization).containsOnly(expected);
            }
        } finally {
            server.stop(0);
        }
    }

    /** 仅显式运行时外发完全合成的对话，正常测试不依赖账号、网络、数据库或真实模型。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "LANGFUSE_VERIFY_LIVE", matches = "true")
    void verifyLiveLangfuseWithSyntheticApplicationToolLoop() throws Exception {
        var properties = new LangfuseProperties(true, System.getenv("LANGFUSE_BASE_URL"),
                System.getenv("LANGFUSE_PUBLIC_KEY"), System.getenv("LANGFUSE_SECRET_KEY"),
                true, "development", "langfuse-integration-verification", 1.0, null);
        try (var tracing = new LangfuseConfiguration().langfuseTracing(properties, JSON, new DefaultLangfuseContentMasker())) {
            String turnId = exerciseApplication(tracing);
            tracing.flush();
            System.out.println("LANGFUSE_VERIFIED_TRACE_ID=" + tracing.traceId(turnId));
        }
    }

    /** 真实的 AbstractNode → ToolLoopRunner → 流式 LangChain4j → listener → repository → tracing 路径。 */
    private String exerciseApplication(LangfuseTracing tracing) throws Exception {
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String response;
            if (!request.path("stream").asBoolean(false)) {
                response = """
                        {"id":"memory-response","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                        """;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                boolean hasToolResult = false;
                for (JsonNode message : request.path("messages")) {
                    if ("tool".equals(message.path("role").asText())) hasToolResult = true;
                }
                String delta = hasToolResult ? "{\"content\":\"演示天气：晴\"}"
                        : "{\"tool_calls\":[{\"index\":0,\"id\":\"call-weather\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]}";
                response = "data: {\"id\":\"response\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"response\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                        + (hasToolResult ? "stop" : "tool_calls") + "\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}\n\n"
                        + "data: [DONE]\n\n";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        model.start();
        try {
            var repository = new TraceRepository(mock(TurnMapper.class), mock(NodeExecMapper.class),
                    mock(LlmCallMapper.class), mock(ToolCallMapper.class), JSON, tracing);
            var flow = new FlowProperties(new FlowProperties.Model("http://127.0.0.1:" + model.getAddress().getPort(),
                    "sk-synthetic-model-key", "test-model", "test-model", 0.0, 5, false), null, null);
            var models = new ModelConfig().modelRegistry(flow, new TraceChatModelListener(repository, JSON));
            var registry = new TurnRegistry();
            String turnId = "langfuse-verification-" + UUID.randomUUID();
            String sessionId = "langfuse-synthetic-session";
            repository.insertTurn(turnId, sessionId, "synthetic-user", 1, "今天杭州天气怎么样", Map.of());
            registry.register(new TurnContext(sessionId, turnId, event -> {}));
            var support = new NodeSupport(models, repository, registry, JSON, flow);
            var node = new AbstractNode(support) {
                @Override public String id() { return "biz_query"; }
                @Override public NodeType type() { return NodeType.TOOL_AGENT; }
                @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
                    FlowTool tool = new FlowTool() {
                        @Override public ToolSpecification specification() {
                            return ToolSpecification.builder().name("get_weather").description("Synthetic weather")
                                    .parameters(JsonObjectSchema.builder().addStringProperty("city").required("city").build()).build();
                        }
                        @Override public String execute(Map<String, Object> args, ToolContext context) {
                            return "{\"city\":\"杭州\",\"condition\":\"晴\",\"phone\":\"13800138000\",\"email\":\"alice@example.com\"}";
                        }
                    };
                    var result = streamWithTools(scope, "answer", List.of(UserMessage.from(state.userInput())), List.of(tool), 4);
                    return reply(result.text());
                }
            };
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var state = new FlowState(Map.of(FlowState.TURN_ID, turnId, FlowState.SESSION_ID, sessionId,
                        FlowState.USER_INPUT, "今天杭州天气怎么样"));
                executor.submit(() -> node.apply(state)).get();
                repository.finishTurn(turnId, "演示天气：晴", "START", "biz_query", List.of("biz_query"), Map.of(), "SUCCESS", null, 10);
                // 在 root 已结束后另一个线程调用记忆模型，仍应是同一 trace 的子观察。
                executor.submit(() -> models.memory().chat(ChatRequest.builder().messages(List.of(UserMessage.from("Extract synthetic memory"))).build(),
                        ChatRequestOptions.builder().addListenerAttribute(TraceKeys.TURN_ID, turnId)
                                .addListenerAttribute(TraceKeys.SESSION_ID, sessionId)
                                .addListenerAttribute(TraceKeys.NODE_ID, "memory")
                                .addListenerAttribute(TraceKeys.PURPOSE, "extract_memory").build())).get();
            } finally {
                registry.unregister(turnId);
            }
            return turnId;
        } finally {
            model.stop(0);
        }
    }

    private static LangfuseProperties properties(boolean content) {
        return new LangfuseProperties(true, "http://localhost:3000", "pk-lf-test-public", "sk-lf-test-secret",
                content, "test", "test-release", 1.0, null);
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> name.equals(span.getName())).findFirst().orElseThrow();
    }

    private static String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}

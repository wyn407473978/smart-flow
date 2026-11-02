package com.example.smartflow.config;

import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.trace.TraceChatModelListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModelConfigTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Bearer ", "${DEEPSEEK_API_KEY}"})
    void invalidKeyFailsAtStartupWithConfigurationHint(String apiKey) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class, ModelConfig.class)
                .withBean(TraceChatModelListener.class, () -> mock(TraceChatModelListener.class))
                .withPropertyValues("flow.model.base-url=https://api.deepseek.com",
                        "flow.model.chat-model=chat-test", "flow.model.router-model=router-test");
        if (apiKey != null) {
            runner = runner.withPropertyValues("flow.model.api-key=" + apiKey);
        }
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("flow.model.api-key")
                    .hasStackTraceContaining("DEEPSEEK_API_KEY");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk-secret key", "sk-secret\nkey", "sk-secret\u0000key"})
    void malformedKeyIsRejectedWithoutIncludingTheSecretInTheError(String apiKey) {
        FlowProperties.Model model = new FlowProperties.Model("https://api.deepseek.com", apiKey,
                "chat-test", "router-test", 0.0, 5, false);
        assertThatThrownBy(() -> new ModelConfig().modelRegistry(new FlowProperties(model, null, null),
                mock(TraceChatModelListener.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flow.model.api-key")
                .hasMessageNotContaining("sk-secret");
    }

    @ParameterizedTest
    @MethodSource("modelInputs")
    void everyModelSendsConfiguredAuthenticationAndReasoningEffort(String apiKey, String reasoningEffort) throws Exception {
        List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new ObjectMapper().readTree(request));
            boolean streaming = request.matches("(?s).*\"stream\"\\s*:\\s*true.*");
            String response = streaming
                    ? "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            FlowProperties.Model model = new FlowProperties.Model(
                    "http://127.0.0.1:" + server.getAddress().getPort(), apiKey,
                    "chat-test", "router-test", 0.0, 5, false, "memory-test", reasoningEffort);
            ModelRegistry models = new ModelConfig().modelRegistry(new FlowProperties(model, null, null),
                    mock(TraceChatModelListener.class));
            assertThat(models.chat().chat("hello")).isEqualTo("ok");
            assertThat(models.router().chat("hello")).isEqualTo("ok");
            assertThat(models.memory().chat("hello")).isEqualTo("ok");
            CompletableFuture<ChatResponse> streamed = new CompletableFuture<>();
            models.streaming().chat(List.of(UserMessage.from("hello")), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {}

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    streamed.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    streamed.completeExceptionally(error);
                }
            });
            assertThat(streamed.get(10, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("ok");
            assertThat(authorizationHeaders).containsExactly(
                    "Bearer sk-test-key", "Bearer sk-test-key", "Bearer sk-test-key", "Bearer sk-test-key");
            assertThat(requests).hasSize(4).allSatisfy(request -> {
                if (reasoningEffort == null) {
                    assertThat(request.has("reasoning_effort")).isFalse();
                } else {
                    assertThat(request.path("reasoning_effort").asText()).isEqualTo(reasoningEffort);
                }
            });
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> modelInputs() {
        return Stream.of("sk-test-key", " \tsk-test-key\n", "Bearer sk-test-key", " bearer  sk-test-key ")
                .flatMap(key -> Stream.of(Arguments.of(key, null), Arguments.of(key, "none")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlowProperties.class)
    static class BindingConfig {}
}

package com.example.smartflow.config;

import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.trace.TraceChatModelListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 模型实例。所有模型都挂上 {@link TraceChatModelListener}，
 * 这样无论哪个节点、用哪种方式调用，真实请求和响应都会被落库。
 */
@Configuration
public class ModelConfig {

    @Bean
    public ModelRegistry modelRegistry(FlowProperties props, TraceChatModelListener traceListener) {
        FlowProperties.Model m = props.model();
        Duration timeout = Duration.ofSeconds(m.timeoutSeconds() == null ? 60 : m.timeoutSeconds());
        boolean logHttp = Boolean.TRUE.equals(m.logHttp());

        ChatModel chat = OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(m.apiKey())
                .modelName(m.chatModel())
                .temperature(m.temperature())
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        ChatModel router = OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(m.apiKey())
                .modelName(m.routerModel())
                .temperature(0.0)
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        StreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(m.apiKey())
                .modelName(m.chatModel())
                .temperature(m.temperature())
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        ChatModel memory = m.memoryModel().equals(m.routerModel()) ? router : OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(m.apiKey())
                .modelName(m.memoryModel())
                .temperature(0.0)
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        return new ModelRegistry(chat, router, streaming, memory);
    }
}

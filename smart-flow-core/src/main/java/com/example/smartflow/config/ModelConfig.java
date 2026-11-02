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
        String apiKey = modelApiKey(m);
        Duration timeout = Duration.ofSeconds(m.timeoutSeconds() == null ? 60 : m.timeoutSeconds());
        boolean logHttp = Boolean.TRUE.equals(m.logHttp());

        ChatModel chat = OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(apiKey)
                .modelName(m.chatModel())
                .reasoningEffort(m.reasoningEffort())
                .temperature(m.temperature())
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        ChatModel router = OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(apiKey)
                .modelName(m.routerModel())
                .reasoningEffort(m.reasoningEffort())
                .temperature(0.0)
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        StreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(apiKey)
                .modelName(m.chatModel())
                .reasoningEffort(m.reasoningEffort())
                .temperature(m.temperature())
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        ChatModel memory = m.memoryModel().equals(m.routerModel()) ? router : OpenAiChatModel.builder()
                .baseUrl(m.baseUrl())
                .apiKey(apiKey)
                .modelName(m.memoryModel())
                .reasoningEffort(m.reasoningEffort())
                .temperature(0.0)
                .timeout(timeout)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .listeners(List.of(traceListener))
                .build();

        return new ModelRegistry(chat, router, streaming, memory);
    }

    private static String modelApiKey(FlowProperties.Model model) {
        String apiKey = model == null || model.apiKey() == null ? "" : model.apiKey().strip();
        // SDK 会自行添加 Bearer，兼容从 Authorization 请求头复制过来的值。
        apiKey = apiKey.replaceFirst("(?i)^Bearer\\s+", "").strip();
        if (apiKey.isBlank() || apiKey.equalsIgnoreCase("Bearer") || apiKey.contains("${")) {
            throw new IllegalArgumentException("flow.model.api-key 未配置有效值。请在启动应用的进程中设置 "
                    + "DEEPSEEK_API_KEY，或直接配置 flow.model.api-key；只填写 API Key 本身，无需 Bearer 前缀。");
        }
        if (apiKey.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("flow.model.api-key 包含空白或控制字符，请检查 API Key 是否完整。");
        }
        return apiKey;
    }
}

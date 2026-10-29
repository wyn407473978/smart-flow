package com.example.smartflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flow")
public record FlowProperties(Model model, Memory memory, Sse sse) {

    public record Model(String baseUrl,
                        String apiKey,
                        String chatModel,
                        String routerModel,
                        Double temperature,
                        Integer timeoutSeconds,
                        Boolean logHttp) {
    }

    public record Memory(Integer recentMessages) {
        public Memory {
            if (recentMessages == null || recentMessages <= 0) {
                recentMessages = 12;
            }
        }
    }

    public record Sse(Integer timeoutSeconds) {
        public Sse {
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 180;
            }
        }
    }
}

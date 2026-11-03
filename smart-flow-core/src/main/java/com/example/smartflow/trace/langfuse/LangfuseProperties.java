package com.example.smartflow.trace.langfuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** Langfuse 为可选的外部观测服务，未启用时不创建 exporter，也不发送数据。 */
@ConfigurationProperties("flow.langfuse")
public record LangfuseProperties(boolean enabled, String baseUrl, String publicKey, String secretKey,
                                 boolean captureContent, String environment, String release,
                                 Double sampleRate, Integer maxContentChars) {
    public LangfuseProperties {
        environment = environment == null || environment.isBlank() ? "development" : environment;
        sampleRate = sampleRate == null ? 1.0 : sampleRate;
        maxContentChars = maxContentChars == null ? 32_000 : maxContentChars;
        if (!Double.isFinite(sampleRate) || sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("flow.langfuse.sample-rate 必须在 0 到 1 之间");
        }
        if (maxContentChars < 256) {
            throw new IllegalArgumentException("flow.langfuse.max-content-chars 必须至少为 256");
        }
        if (enabled) {
            if (baseUrl == null || baseUrl.isBlank() || publicKey == null || publicKey.isBlank()
                    || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("启用 Langfuse 时必须配置 LANGFUSE_BASE_URL、LANGFUSE_PUBLIC_KEY、LANGFUSE_SECRET_KEY");
            }
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("flow.langfuse.base-url 必须是有效的 HTTP(S) 地址");
            }
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("flow.langfuse.base-url 必须是无认证信息、查询参数或片段的 HTTP(S) 地址");
            }
        }
    }

    public String tracesEndpoint() {
        return baseUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
    }

    @Override
    public String toString() {
        return "LangfuseProperties[enabled=" + enabled + ", captureContent=" + captureContent
                + ", environment=" + environment + "]";
    }
}

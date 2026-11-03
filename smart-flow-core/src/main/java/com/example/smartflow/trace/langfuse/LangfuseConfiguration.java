package com.example.smartflow.trace.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LangfuseProperties.class)
@ConditionalOnProperty(prefix = "flow.langfuse", name = "enabled", havingValue = "true")
public class LangfuseConfiguration {
    @Bean
    @ConditionalOnMissingBean(LangfuseContentMasker.class)
    public LangfuseContentMasker langfuseContentMasker() {
        return new DefaultLangfuseContentMasker();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LangfuseTracing.class)
    public LangfuseTracing langfuseTracing(LangfuseProperties properties, ObjectMapper mapper,
                                           LangfuseContentMasker masker) {
        String credentials = Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));
        var exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.tracesEndpoint())
                .addHeader("Authorization", "Basic " + credentials)
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofSeconds(5))
                .build();
        var processor = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(2048).setMaxExportBatchSize(256)
                .setScheduleDelay(Duration.ofSeconds(2)).build();
        // 独立 provider 只发送本应用的 AI spans，不覆盖应用已有的全局 OTel，也不采集 HTTP/SQL 噪声。
        var provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "smart-flow"))))
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(properties.sampleRate())))
                .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(properties.maxContentChars()).build())
                .addSpanProcessor(processor).build();
        return new LangfuseTracing(provider, properties, mapper, masker);
    }
}

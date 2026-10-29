package com.example.smartflow.autoconfigure.web;

import com.example.smartflow.autoconfigure.FlowCoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 工作流框架 Web 层的自动配置：SSE 对话接口、调试查询接口（/api/**）。测试页面在 classpath:/static/index.html。
 * 业务工程依赖 smart-flow-web 后自动生效。
 */
@AutoConfiguration(after = FlowCoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ComponentScan("com.example.smartflow.web")
public class FlowWebAutoConfiguration {
}

package com.example.smartflow.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智慧民生示例工作流的启动类。
 * <p>
 * 框架的 Bean（引擎、根路由、记忆、trace、Web 接口）由 smart-flow-core / smart-flow-web 的自动配置注册，
 * 这里只扫描本工作流自己的包：节点、工具。
 */
@SpringBootApplication
public class SmartFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartFlowApplication.class, args);
    }
}

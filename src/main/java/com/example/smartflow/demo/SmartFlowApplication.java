package com.example.smartflow.demo;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 智慧民生示例工作流的启动类。
 * <p>
 * 过渡期：框架代码和业务代码还在同一个模块里，这里先扫描整个 com.example.smartflow；
 * 拆成多模块以后由框架的自动配置负责注册框架 Bean，这里只扫描业务包。
 */
@SpringBootApplication(scanBasePackages = "com.example.smartflow")
@ConfigurationPropertiesScan("com.example.smartflow")
@MapperScan(basePackages = "com.example.smartflow", annotationClass = Mapper.class)
public class SmartFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartFlowApplication.class, args);
    }
}

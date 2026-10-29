package com.example.smartflow.autoconfigure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架打成 jar 被业务工程依赖时，这些资源必须在固定位置，否则启动后才报错。
 */
class FrameworkResourcesTest {

    /** MyBatis 的 addMapper 会按"接口全名 → 同路径 .xml"加载 SQL，XML 放错位置会导致所有语句找不到 */
    @Test
    void mapperXmlSitsNextToInterface() throws IOException {
        for (Class<?> mapper : FlowCoreAutoConfiguration.FRAMEWORK_MAPPERS) {
            String path = mapper.getName().replace('.', '/') + ".xml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "缺少 " + path);
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(xml.contains("namespace=\"" + mapper.getName() + "\""), path + " 的 namespace 应为 " + mapper.getName());
            }
        }
    }

    @Test
    void autoConfigurationIsRegistered() throws IOException {
        String path = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少 " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains(FlowCoreAutoConfiguration.class.getName()), text);
        }
    }

    @Test
    void schemaScriptIsPackaged() {
        assertNotNull(getClass().getClassLoader().getResource("db/smart-flow-schema.sql"),
                "业务工程的 spring.sql.init.schema-locations 引用的是 classpath:db/smart-flow-schema.sql");
    }
}

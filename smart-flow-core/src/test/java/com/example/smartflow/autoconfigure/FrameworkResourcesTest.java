package com.example.smartflow.autoconfigure;

import com.example.smartflow.trace.LlmCallMapper;
import com.example.smartflow.trace.NodeExecMapper;
import com.example.smartflow.trace.ToolCallMapper;
import com.example.smartflow.trace.TurnMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架打成 jar 被业务工程依赖时，这些资源必须在固定位置，否则启动后才报错。
 */
class FrameworkResourcesTest {

    /** 验证自动注册能加载 XML，接口方法能绑定语句，动态 SQL 能展开，且每组只操作自己的表。 */
    @Test
    void traceMappersBindStatementsForTheirOwnTable() {
        Configuration configuration = new Configuration();
        new FlowCoreAutoConfiguration().smartFlowMybatisCustomizer().customize(configuration);
        Map<Class<?>, String> traceTables = Map.of(
                TurnMapper.class, "flow_turn",
                NodeExecMapper.class, "flow_node_exec",
                LlmCallMapper.class, "flow_llm_call",
                ToolCallMapper.class, "flow_tool_call");
        Pattern tablePattern = Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE)\\s+([a-z_]+)");

        traceTables.forEach((mapper, table) -> {
            assertTrue(configuration.hasMapper(mapper));
            for (var method : mapper.getDeclaredMethods()) {
                var command = new MapperMethod.SqlCommand(configuration, mapper, method);
                Map<String, Object> parameters = new HashMap<>();
                for (var parameter : method.getParameters()) {
                    Param annotation = parameter.getAnnotation(Param.class);
                    assertNotNull(annotation, method.getName() + " 的参数应有 @Param");
                    Class<?> type = parameter.getType();
                    Object value = Collection.class.isAssignableFrom(type) ? List.of("turn-1", "turn-2")
                            : type == boolean.class ? true
                            : type == long.class ? 1L
                            : type == int.class || type == Integer.class ? 1 : "sample";
                    parameters.put(annotation.value(), value);
                }
                var boundSql = configuration.getMappedStatement(command.getName()).getBoundSql(parameters);
                Set<String> tables = tablePattern.matcher(boundSql.getSql()).results()
                        .map(match -> match.group(1)).collect(java.util.stream.Collectors.toSet());
                assertEquals(Set.of(table), tables, command.getName());
            }
        });
    }

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

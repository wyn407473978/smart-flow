package com.example.smartflow.autoconfigure;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.memory.MemoryMapper;
import com.example.smartflow.session.SessionMapper;
import com.example.smartflow.trace.TraceMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

/**
 * 工作流框架核心的自动配置：业务工程依赖 smart-flow-core 后自动生效，不需要扫描框架的包。
 * <p>
 * 注册的内容：
 * <ul>
 *   <li>配置项 {@code flow.*}（{@link FlowProperties}）</li>
 *   <li>引擎、根路由节点、路由目录、记忆、trace、模型等框架 Bean</li>
 *   <li>框架自己的三个 MyBatis Mapper（会话、记忆、trace）</li>
 * </ul>
 * 业务节点（继承 AbstractNode 的 @Component）放在业务工程自己的包里，由业务工程的 @SpringBootApplication 扫描。
 */
@AutoConfiguration(after = MybatisAutoConfiguration.class)
@EnableConfigurationProperties(FlowProperties.class)
@ComponentScan(basePackages = {
        "com.example.smartflow.config",
        "com.example.smartflow.engine",
        "com.example.smartflow.llm",
        "com.example.smartflow.memory",
        "com.example.smartflow.node",
        "com.example.smartflow.session",
        "com.example.smartflow.tool",
        "com.example.smartflow.trace"
})
public class FlowCoreAutoConfiguration {

    /** 框架自己的 Mapper，SQL 写在与接口同路径的 XML 里（例如 com/example/smartflow/session/SessionMapper.xml） */
    static final List<Class<?>> FRAMEWORK_MAPPERS = List.of(SessionMapper.class, MemoryMapper.class, TraceMapper.class);

    /**
     * 框架 Mapper 直接注册进 MyBatis 配置，不用 @MapperScan：
     * 用了 @MapperScan 之后 MyBatis 会关闭自己的自动扫描，业务工程里的 @Mapper 接口就扫不到了。
     * <p>
     * 同时打开 callSettersOnNulls：结果映射到 Map 时值为 null 的列也保留 key，测试页依赖这些字段。
     * 注意这是全局设置，业务 Mapper 返回 Map 时也会生效。
     */
    @Bean
    public ConfigurationCustomizer smartFlowMybatisCustomizer() {
        return configuration -> {
            configuration.setCallSettersOnNulls(true);
            for (Class<?> mapper : FRAMEWORK_MAPPERS) {
                if (!configuration.hasMapper(mapper)) {
                    configuration.addMapper(mapper);
                }
            }
        };
    }

    @Bean
    public SessionMapper sessionMapper(SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(SessionMapper.class);
    }

    @Bean
    public MemoryMapper memoryMapper(SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(MemoryMapper.class);
    }

    @Bean
    public TraceMapper traceMapper(SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(TraceMapper.class);
    }
}

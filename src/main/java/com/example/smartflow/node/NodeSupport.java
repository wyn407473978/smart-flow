package com.example.smartflow.node;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * 节点公共依赖打包在一起，子类构造器只需要 super(support)，以后加公共能力不用改每个节点。
 */
@Component
public class NodeSupport {

    private final ModelRegistry models;
    private final TraceRepository trace;
    private final TurnRegistry turns;
    private final ObjectMapper mapper;
    private final FlowProperties props;

    /** 专门解析模型输出的 JSON：枚举大小写不敏感、多余字段忽略、未知枚举值当 null */
    private final ObjectMapper llmJsonMapper = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build();

    public NodeSupport(ModelRegistry models, TraceRepository trace, TurnRegistry turns,
                       ObjectMapper mapper, FlowProperties props) {
        this.models = models;
        this.trace = trace;
        this.turns = turns;
        this.mapper = mapper;
        this.props = props;
    }

    public ModelRegistry models() { return models; }
    public TraceRepository trace() { return trace; }
    public TurnRegistry turns() { return turns; }
    public ObjectMapper mapper() { return mapper; }
    public ObjectMapper llmJsonMapper() { return llmJsonMapper; }
    public FlowProperties props() { return props; }
}

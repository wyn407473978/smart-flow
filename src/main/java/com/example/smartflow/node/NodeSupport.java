package com.example.smartflow.node;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.llm.LlmJson;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.memory.SimpleTokenEstimator;
import com.example.smartflow.memory.TokenEstimator;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TokenEstimator tokenEstimator;


    @Autowired
    public NodeSupport(ModelRegistry models, TraceRepository trace, TurnRegistry turns,
                       ObjectMapper mapper, FlowProperties props, TokenEstimator tokenEstimator) {
        this.models = models;
        this.trace = trace;
        this.turns = turns;
        this.mapper = mapper;
        this.props = props;
        this.tokenEstimator = tokenEstimator;
    }

    /** 测试或非 Spring 环境：使用默认的 token 估算 */
    public NodeSupport(ModelRegistry models, TraceRepository trace, TurnRegistry turns,
                       ObjectMapper mapper, FlowProperties props) {
        this(models, trace, turns, mapper, props, new SimpleTokenEstimator());
    }

    public ModelRegistry models() { return models; }
    public TraceRepository trace() { return trace; }
    public TurnRegistry turns() { return turns; }
    public ObjectMapper mapper() { return mapper; }
    /** 专门解析模型输出的 JSON：枚举大小写不敏感、多余字段忽略、未知枚举值当 null */
    public ObjectMapper llmJsonMapper() { return LlmJson.MAPPER; }
    public FlowProperties props() { return props; }
    public TokenEstimator tokenEstimator() { return tokenEstimator; }
}

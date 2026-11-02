package com.example.smartflow.node;

import com.example.smartflow.config.FlowProperties;
import com.example.smartflow.engine.TurnRegistry;
import com.example.smartflow.llm.LlmJson;
import com.example.smartflow.llm.ModelRegistry;
import com.example.smartflow.llm.NodeModelClient;
import com.example.smartflow.memory.NodeContextBuilder;
import com.example.smartflow.memory.SimpleTokenEstimator;
import com.example.smartflow.memory.TokenEstimator;
import com.example.smartflow.tool.ToolLoopRunner;
import com.example.smartflow.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 汇集节点公共依赖及可复用的无状态组件，子类构造器只需要 super(support)。
 * 模型调用、工具循环、上下文组装的实现分别由对应组件负责。
 */
@Component
public class NodeSupport {

    private final ModelRegistry models;
    private final TraceRepository traceRepository;
    private final TurnRegistry turnRegistry;
    private final ObjectMapper mapper;
    private final FlowProperties props;
    private final TokenEstimator tokenEstimator;
    private final NodeModelClient modelClient;
    private final ToolLoopRunner toolLoopRunner;
    private final NodeContextBuilder contextBuilder;

    @Autowired
    public NodeSupport(ModelRegistry models, TraceRepository traceRepository, TurnRegistry turnRegistry,
                       ObjectMapper mapper, FlowProperties props, TokenEstimator tokenEstimator) {
        this.models = models;
        this.traceRepository = traceRepository;
        this.turnRegistry = turnRegistry;
        this.mapper = mapper;
        this.props = props;
        this.tokenEstimator = tokenEstimator;
        this.modelClient = new NodeModelClient(models, props, LlmJson.MAPPER);
        this.toolLoopRunner = new ToolLoopRunner(modelClient, traceRepository, LlmJson.MAPPER);
        this.contextBuilder = new NodeContextBuilder(props, tokenEstimator);
    }

    /** 测试或非 Spring 环境：使用默认的 token 估算 */
    public NodeSupport(ModelRegistry models, TraceRepository traceRepository, TurnRegistry turnRegistry,
                       ObjectMapper mapper, FlowProperties props) {
        this(models, traceRepository, turnRegistry, mapper, props, new SimpleTokenEstimator());
    }

    public ModelRegistry models() { return models; }
    public TraceRepository traceRepository() { return traceRepository; }
    public TurnRegistry turnsRepository() { return turnRegistry; }
    public ObjectMapper mapper() { return mapper; }
    /** 专门解析模型输出的 JSON：枚举大小写不敏感、多余字段忽略、未知枚举值当 null */
    public ObjectMapper llmJsonMapper() { return LlmJson.MAPPER; }
    public FlowProperties props() { return props; }

    /** 助手人设（名字、语气），各节点拼提示词时使用 */
    public FlowProperties.Assistant assistant() { return props.assistant(); }
    public TokenEstimator tokenEstimator() { return tokenEstimator; }

    /** 组件本身无会话状态，可供多个节点和轮次复用。 */
    public NodeModelClient modelClient() { return modelClient; }
    public ToolLoopRunner toolLoopRunner() { return toolLoopRunner; }
    public NodeContextBuilder contextBuilder() { return contextBuilder; }
}

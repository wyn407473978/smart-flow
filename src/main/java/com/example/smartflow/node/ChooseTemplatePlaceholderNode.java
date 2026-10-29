package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * C2：活动模版确认 —— 最小闭环里的占位节点，只用来验证"C1 收齐参数后同一轮内流转到 C2"。
 * 真正实现时换成 TOOL_AGENT 类型的节点：挂载"查询可用模版"工具，引导用户选模版。
 */
@Component
public class ChooseTemplatePlaceholderNode extends AbstractNode {

    public ChooseTemplatePlaceholderNode(NodeSupport support) {
        super(support);
    }

    @Override
    public String id() {
        return FlowNames.CHOOSE_TEMPLATE;
    }

    @Override
    public NodeType type() {
        return NodeType.PLACEHOLDER;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) {
        return reply(emitText(scope, "下一步是选择活动模版。（C2 模版确认节点尚未实现，这里是占位回复。）"));
    }
}

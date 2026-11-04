package com.example.smartflow.demo.node;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.TaskStackEdit;

import java.util.Map;

/** 旧会话兼容入口。保留任务及已收集数据，转到统一收集节点；新任务不经过这里。 */
/** 旧版工作流的回归测试夹具，不参与 Demo 的 Spring 节点注册。 */
public class ChooseTemplateNode extends AbstractNode {
    public ChooseTemplateNode(NodeSupport support) { super(support); }

    @Override public String id() { return MinshengNames.CHOOSE_TEMPLATE; }
    @Override public NodeType type() { return NodeType.of("COMPATIBILITY"); }
    @Override public String stageDescription() { return "继续收集活动信息（旧阶段兼容入口）"; }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) {
        TaskStackEdit edit = editTasks(state);
        edit.requireActiveTask(id());
        edit.advanceTo(MinshengNames.COLLECT_TARGET);
        Map<String, Object> updates = updates();
        goTo(updates, MinshengNames.COLLECT_TARGET);
        return edit.writeTo(updates);
    }
}

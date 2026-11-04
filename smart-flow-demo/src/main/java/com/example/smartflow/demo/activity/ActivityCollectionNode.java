package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.*;
import com.example.smartflow.node.route.RouteSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 四个收集节点共享的生命周期：校验并保存本轮属于自己的参数，再交回活动路由。
 * 追问由活动路由统一组织（已记录什么、问哪几项由代码决定，措辞交给追问模型），
 * 收集节点只提供自己这一项的描述和兜底问题。
 */
public abstract class ActivityCollectionNode extends AbstractNode {
    protected ActivityCollectionNode(NodeSupport support) { super(support); }
    public abstract String section();
    protected abstract String label();
    /** 代码追问文案：ask-mode 为 code，或追问模型超时、出错时使用 */
    protected abstract String question(TaskFrame task);
    /** 交给追问模型的"要问什么"，可以带示例或可选项 */
    protected abstract String askItem(TaskFrame task);
    /** 已保存的值怎么告诉用户，例如"发送区域：杭州市西湖区"；没什么可说时返回 null */
    protected abstract String describeSaved(TaskFrame task);
    /** 返回校验错误（不带句末标点，会和其他提示连成一句）；成功返回 null。失败时不得覆盖原参数。 */
    protected abstract String collect(TaskFrame task, ActivityInput input, NodeScope scope);
    @Override public NodeType type() { return NodeType.SLOT_FILLING; }
    @Override public String stageDescription() { return "收集或修改" + label() + "，也可先提供其他活动参数"; }
    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(new RouteSpec(MinshengNames.ACTIVITY_CREATE, section().toUpperCase(), label(),
                stageDescription(), List.of(), null, section().equals("region"), 10 + ActivityData.SECTIONS.indexOf(section())));
    }

    @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());
        Map<String, Object> updates = updates();
        ActivityInput input = state.<ActivityInput>value(ActivityCreateRouterNode.INPUT).orElse(ActivityInput.empty());
        String error = collect(task, input, scope);
        Map<String, String> errors = new LinkedHashMap<>(state.<Map<String, String>>value(ActivityCreateRouterNode.ERRORS).orElse(Map.of()));
        if (error != null) {
            errors.put(section(), error);
            if (!task.getEditingSlots().contains(section())) task.getEditingSlots().add(section());
        } else if (input.touches(section())) {
            task.getEditingSlots().remove(section());
        }
        updates.put(ActivityCreateRouterNode.ERRORS, errors);
        edit.advanceTo(MinshengNames.ACTIVITY_CREATE);
        goTo(updates, MinshengNames.ACTIVITY_CREATE);
        return edit.writeTo(updates);
    }
}

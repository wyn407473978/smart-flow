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

/** 四个收集节点共享的生命周期：处理本轮数据，再交回活动路由；只有被选中追问的节点输出问题。 */
public abstract class ActivityCollectionNode extends AbstractNode {
    protected ActivityCollectionNode(NodeSupport support) { super(support); }
    public abstract String section();
    protected abstract String label();
    protected abstract String question(TaskFrame task);
    /** 返回校验错误；成功返回 null。失败时不得覆盖原参数。 */
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
        if (state.<Boolean>value(ActivityCreateRouterNode.ASK).orElse(false)) {
            String error = state.<Map<String, String>>value(ActivityCreateRouterNode.ERRORS).orElse(Map.of()).get(section());
            String text = (error == null ? "" : error + "\n") + question(task);
            task.getSlots().put("waitingSection", section());
            edit.advanceTo(MinshengNames.ACTIVITY_CREATE);
            task.setAwaiting(text);
            updates.put(FlowState.REPLIES, emitText(scope, ActivityData.summary(task) + "\n\n" + text));
        } else {
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
        }
        return edit.writeTo(updates);
    }
}

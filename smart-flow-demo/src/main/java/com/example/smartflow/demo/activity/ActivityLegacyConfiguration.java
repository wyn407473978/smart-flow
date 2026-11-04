package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

/** 旧会话保留参数后进入新路由；不把旧版收集流程注册回 Demo。 */
@Configuration(proxyBeanMethods = false)
public class ActivityLegacyConfiguration {
    @Bean AbstractNode legacyActivityCollect(NodeSupport support) { return legacy(support, MinshengNames.COLLECT_TARGET); }
    @Bean AbstractNode legacyActivityTemplate(NodeSupport support) { return legacy(support, MinshengNames.CHOOSE_TEMPLATE); }
    @Bean AbstractNode legacyActivitySubmit(NodeSupport support) { return legacy(support, MinshengNames.SUBMIT_ACTIVITY); }

    private AbstractNode legacy(NodeSupport support, String nodeId) {
        return new AbstractNode(support) {
            @Override public String id() { return nodeId; }
            @Override public NodeType type() { return NodeType.of("COMPATIBILITY"); }
            @Override public String stageDescription() { return "继续短信活动创建，重新校验旧版参数"; }
            @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) {
                TaskStackEdit edit = editTasks(state);
                // 旧版 editingSlots 使用字段名，新版按四个分组处理。
                var editing = edit.requireActiveTask(id()).getEditingSlots();
                if (editing.remove("gender") | editing.remove("ageMin") | editing.remove("ageMax")) {
                    if (!editing.contains("audience")) editing.add("audience");
                }
                if (editing.remove("activityTime") && !editing.contains("time")) editing.add("time");
                edit.advanceTo(MinshengNames.ACTIVITY_CREATE);
                return edit.writeTo(goTo(updates(), MinshengNames.ACTIVITY_CREATE));
            }
        };
    }
}

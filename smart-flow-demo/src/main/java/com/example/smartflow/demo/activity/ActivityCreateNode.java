package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.node.slot.SlotGroupNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 短信活动创建的入口：区域、人群、时间、模版四个收集节点组成一个无序组，用户可以按任意顺序、一句话提供多项。
 * 抽取、校验合并、追问都由框架的 {@link SlotGroupNode} / {@link com.example.smartflow.node.slot.SlotFillingNode} 完成，
 * 这里只声明成员和收齐后去确认提交。
 */
@Component
public class ActivityCreateNode extends SlotGroupNode {

    public ActivityCreateNode(NodeSupport support, ActivityRegionNode region, ActivityAudienceNode audience,
                              ActivityTimeNode time, ActivityTemplateNode template) {
        super(support, List.of(region, audience, time, template));
    }

    @Override public String id() { return MinshengNames.ACTIVITY_CREATE; }

    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.taskEntry(FlowNames.ROUTER, MinshengNames.TASK_ACTIVITY_CREATE, "短信活动创建",
                "用户要创建、发送、安排短信活动，或继续提供活动区域、人群、发送时间、短信内容和模版选择",
                List.of("创建一个短信活动", "给西湖区20岁以上男性发短信", "明天9点发送健康讲座通知"), 10));
    }

    @Override protected String stageGoal() {
        return "收集创建短信活动需要的发送区域、发送人群、发送时间和短信模版，顺序不限；短信模版须从三个推荐中由用户选定";
    }

    @Override protected String taskContext() { return "用户正在创建一个短信发送活动。"; }

    @Override protected String nextNode() { return MinshengNames.ACTIVITY_SUBMIT; }

    @Override protected String unreadableText() { return "这句话我没能识别出活动信息"; }
}

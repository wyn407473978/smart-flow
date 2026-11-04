package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import org.springframework.stereotype.Component;

@Component
public class ActivityRegionNode extends ActivityCollectionNode {
    public ActivityRegionNode(NodeSupport support) { super(support); }
    @Override public String id() { return MinshengNames.ACTIVITY_REGION; }
    @Override public String section() { return "region"; }
    @Override protected String label() { return "短信发送区域"; }
    @Override protected String question(TaskFrame task) { return "短信要发到哪个区域？例如：杭州市西湖区蒋村街道。"; }
    @Override protected String collect(TaskFrame task, ActivityInput input, NodeScope scope) {
        if (input.region() == null) return null;
        String region = input.region().strip();
        if (region.isEmpty() || region.length() > 100) return "请提供有效的发送区域（不超过100字）。";
        task.getSlots().put("region", region);
        return null;
    }
}

package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class ActivityTimeNode extends ActivityCollectionNode {
    private final Clock clock;
    @Autowired public ActivityTimeNode(NodeSupport support) { this(support, Clock.system(ActivityData.ZONE)); }
    public ActivityTimeNode(NodeSupport support, Clock clock) { super(support); this.clock = clock; }
    @Override public String id() { return MinshengNames.ACTIVITY_TIME; }
    @Override public String section() { return "time"; }
    @Override protected String label() { return "短信发送时间"; }
    @Override protected String question(TaskFrame task) {
        return "请提供未来的短信发送日期和具体时刻，例如明天上午9点（北京时间）。";
    }
    @Override protected String collect(TaskFrame task, ActivityInput input, NodeScope scope) {
        if (input.sendTime() == null) return null;
        if (!ActivityData.future(input.sendTime(), clock))
            return "发送时间必须是有效的未来时间，格式为 yyyy-MM-dd HH:mm:ss；请补充明确的日期和时刻。";
        task.getSlots().put("sendTime", input.sendTime());
        return null;
    }
}

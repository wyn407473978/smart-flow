package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;

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
    @Override protected String askItem(TaskFrame task) { return "具体的发送日期和时刻（北京时间，需要晚于现在）"; }
    @Override protected String describeSaved(TaskFrame task) {
        Object time = task.getSlots().get("sendTime");
        return ActivityData.future(time, clock) ? "发送时间：" + ActivityData.display(time) + "（北京时间）" : null;
    }
    @Override protected String collect(TaskFrame task, ActivityInput input, NodeScope scope) {
        if (input.sendTime() == null) return null;
        String text = input.sendTime().strip();
        LocalDateTime time = ActivityData.parse(text);
        if (time == null) {
            // 只说了"明天""下周六上午"时，路由按约定填空字符串
            return text.matches("\\d{4}-\\d{2}-\\d{2}.*") ? "日期 " + text.substring(0, 10) + " 不存在"
                    : "发送时间还差具体的日期或时刻";
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(ActivityData.ZONE));
        if (!time.isAfter(now))
            return "发送时间 " + ActivityData.display(text) + " 已经过去了，需要晚于现在（北京时间 "
                    + now.format(ActivityData.DISPLAY_FORMAT) + "）";
        task.getSlots().put("sendTime", text);
        return null;
    }
}

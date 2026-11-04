package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.TaskStackEdit;
import com.example.smartflow.node.confirm.ConfirmNode;
import com.example.smartflow.node.route.RouteSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Component
public class ActivitySubmitNode extends ConfirmNode {
    private final MockSmsActivityClient client;
    private final Clock clock;
    @Autowired public ActivitySubmitNode(NodeSupport support, MockSmsActivityClient client) {
        this(support, client, Clock.system(ActivityData.ZONE));
    }
    public ActivitySubmitNode(NodeSupport support, MockSmsActivityClient client, Clock clock) {
        super(support); this.client = client; this.clock = clock;
    }
    @Override public String id() { return MinshengNames.ACTIVITY_SUBMIT; }
    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.oneShot(MinshengNames.ACTIVITY_CREATE, "SUBMIT", "短信活动最终提交",
                "仅在区域、人群、未来发送时间、用户选定的短信模版都有效时展示汇总，等待明确确认后提交；不能提前进入",
                List.of("确认创建短信活动"), 100));
    }
    @Override protected String actionName() { return "创建短信发送活动"; }
    @Override protected String summary(TaskFrame task) { return ActivityData.summary(task); }
    @Override protected String editNode(TaskFrame task) { return MinshengNames.ACTIVITY_CREATE; }
    @Override protected String confirmText(TaskFrame task) {
        return "请确认是否按以下参数创建短信发送活动：\n" + summary(task)
                + "\n\n回复「确认」后提交，短信按上述时间安排发送；也可以修改参数或取消。";
    }

    @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        TaskFrame task = state.activeTask().orElseThrow();
        if (!ActivityData.ready(task, clock)) {
            TaskStackEdit edit = editTasks(state);
            edit.advanceTo(MinshengNames.ACTIVITY_CREATE);
            return edit.writeTo(goTo(updates(), MinshengNames.ACTIVITY_CREATE));
        }
        return super.execute(state, scope);
    }

    @Override protected CommitResult commit(TaskFrame task, String idempotencyKey) {
        if (!ActivityData.ready(task, clock)) return CommitResult.failed("参数不完整或发送时间已过期，请修改发送时间或其他参数。");
        Map<String, Object> s = task.getSlots();
        var request = new MockSmsActivityClient.Request((String) s.get("region"), (String) s.get("gender"),
                (Integer) s.get("ageMin"), (Integer) s.get("ageMax"), (String) s.get("sendTime"), (String) s.get("smsContent"));
        var result = client.create(idempotencyKey, request);
        if (result == null) return CommitResult.failed("短信活动接口未返回成功结果。");
        return CommitResult.ok("短信活动创建完毕，活动编号 " + result.activityId() + "，计划发送时间 " + request.sendTime() + "（北京时间）。");
    }
}

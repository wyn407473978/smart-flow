package com.example.smartflow.demo.node;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.confirm.ConfirmNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * C3：活动创建 - 确认后创建活动。任务的最后一个阶段。
 * <p>
 * 演示用：{@link #commit} 没有真正调用业务系统，只用幂等键生成一个固定的活动编号。
 * 接真实接口时，把幂等键作为请求号传给业务系统（或者存进业务表的唯一索引），重复确认不会创建两个活动。
 */
@Component
public class SubmitActivityNode extends ConfirmNode {

    private final CollectTargetNode collectTarget;
    private final ChooseTemplateNode chooseTemplate;

    public SubmitActivityNode(NodeSupport support, CollectTargetNode collectTarget, ChooseTemplateNode chooseTemplate) {
        super(support);
        this.collectTarget = collectTarget;
        this.chooseTemplate = chooseTemplate;
    }

    @Override
    public String id() {
        return MinshengNames.SUBMIT_ACTIVITY;
    }

    @Override
    protected String actionName() {
        return "创建活动";
    }

    @Override
    protected String summary(TaskFrame task) {
        Map<String, Object> slots = task.getSlots();
        return "- 目标人群：" + collectTarget.describe(slots) + "\n"
                + "- 活动模版：" + chooseTemplate.templateName(slots) + "\n"
                + "- 活动时间：" + slots.getOrDefault(ChooseTemplateNode.SLOT_TIME, "（未填写）");
    }

    @Override
    protected CommitResult commit(TaskFrame task, String idempotencyKey) {
        // 同一个任务的幂等键不变，生成的编号也不变：重复确认得到的是同一个活动
        String activityId = "ACT-" + idempotencyKey.replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        log.info("创建活动 id={} slots={}", activityId, task.getSlots());
        return CommitResult.ok("活动已创建成功，编号 " + activityId + "。后续可以在活动管理里查看和发布。");
    }

    /** 修改时从 C1 开始：C1、C2 收齐后会自动往后走，最后回到这里重新确认 */
    @Override
    protected String editNode(TaskFrame task) {
        return MinshengNames.COLLECT_TARGET;
    }
}

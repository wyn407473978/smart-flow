package com.example.smartflow.demo.node;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import com.example.smartflow.node.slot.Slots;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * C2：活动创建 - 确认活动模版和时间。
 * <p>
 * 不声明 routeSpec：任务的中间阶段只能由上一阶段（C1）推进过来。
 * 模版先用固定的几种；以后要从业务系统查可用模版时，可以改成 TOOL_AGENT 节点，或者在 {@link #slots()} 里动态生成可选值。
 */
@Component
public class ChooseTemplateNode extends SlotFillingNode {

    public static final String SLOT_TEMPLATE = "template";
    public static final String SLOT_TIME = "activityTime";

    private static final List<SlotSpec> SLOTS = List.of(
            SlotSpec.enumOf(SLOT_TEMPLATE, "活动模版")
                    .option("ELDERLY_CARE", "敬老关爱")
                    .option("PARENT_CHILD", "亲子活动")
                    .option("HEALTH_TALK", "健康讲座")
                    .option("CULTURE_SPORTS", "文体活动")
                    .describe("根据用户说的活动类型选最接近的一个，例如\"重阳节看望老人\"→ ELDERLY_CARE")
                    .build(),
            SlotSpec.string(SLOT_TIME, "活动时间").maxLength(50)
                    .describe("保留用户原话，例如\"下周六上午\"\"10月31日下午两点\"")
                    .build());

    public ChooseTemplateNode(NodeSupport support) {
        super(support);
    }

    @Override
    public String id() {
        return MinshengNames.CHOOSE_TEMPLATE;
    }

    @Override
    protected List<SlotSpec> slots() {
        return SLOTS;
    }

    @Override
    protected String taskContext() {
        return "用户正在创建一个民生活动，目标人群已经确定。";
    }

    @Override
    protected String stageGoal() {
        return "确认活动使用的模版和活动时间";
    }

    @Override
    protected String nextNode() {
        return MinshengNames.SUBMIT_ACTIVITY;
    }

    @Override
    protected String completedText(Map<String, Object> slots) {
        return "好的，活动模版定为「" + templateName(slots) + "」，时间是" + slots.get(SLOT_TIME) + "。";
    }

    /** 模版的中文名，给确认节点展示用 */
    public String templateName(Map<String, Object> slots) {
        return Slots.display(SLOTS.get(0), slots.get(SLOT_TEMPLATE));
    }
}

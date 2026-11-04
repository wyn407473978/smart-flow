package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.slot.SlotErrors;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 短信发送时间，按北京时间保存为 yyyy-MM-dd HH:mm:ss。模型负责把"明天上午9点"换算成具体时间，
 * 代码严格校验真实日期且晚于当前时间；只说了日期没说时刻时追问，不自动定时刻。已保存的时间过期后要求重新提供。
 */
@Component
public class ActivityTimeNode extends SlotFillingNode {
    /** 只有日期没有具体时刻时，抽取约定输出的值 */
    static final String NEED_TIME = "NEED_TIME";
    private final Clock clock;

    @Autowired public ActivityTimeNode(NodeSupport support) { this(support, Clock.system(ActivityData.ZONE)); }
    public ActivityTimeNode(NodeSupport support, Clock clock) { super(support); this.clock = clock; }

    @Override public String id() { return MinshengNames.ACTIVITY_TIME; }
    @Override protected String stageGoal() { return "收集短信发送时间（北京时间，需要具体到时刻）"; }
    @Override protected String nextNode() { return MinshengNames.ACTIVITY_CREATE; }

    @Override protected List<SlotSpec> slots() {
        return List.of(SlotSpec.string("sendTime", "发送时间").maxLength(19)
                .describe("当前北京时间 " + ActivityData.now(clock).format(ActivityData.TIME_FORMAT)
                        + "。结合当前时间和最近对话，把用户说的日期和具体时刻换算成 yyyy-MM-dd HH:mm:ss（北京时间）；"
                        + "用户上一轮只说“明天”、本轮补“上午9点”时合并理解，只沿用用户已明确的日期。"
                        + "只有日期没有具体时刻（“明天”“下周六上午”）时填 " + NEED_TIME + "，不要自己定时刻。"
                        + "过去或不存在的时间也照实换算，由系统校验，不要改成未来")
                .build());
    }

    @Override protected void normalize(Map<String, Object> extracted) {
        if (extracted.get("sendTime") instanceof String s && s.isBlank()) {
            extracted.put("sendTime", NEED_TIME);
        }
    }

    @Override protected void validateTogether(Map<String, Object> candidate, SlotErrors errors) {
        if (errors.isRejected("sendTime") || !(candidate.get("sendTime") instanceof String text)) return;
        String problem = problem(text);
        if (problem != null) errors.reject(problem, "sendTime");
    }

    /** 已保存的时间过期了（例如确认前拖过了发送时间）：明确告诉用户，按待重新提供处理 */
    @Override protected SlotErrors revalidate(Map<String, Object> slots) {
        SlotErrors errors = new SlotErrors();
        if (ActivityData.parse(slots.get("sendTime")) != null && !ActivityData.future(slots.get("sendTime"), clock)) {
            errors.reject("原定的发送时间 " + ActivityData.display(slots.get("sendTime")) + " 已经过去了", "sendTime");
        }
        return errors;
    }

    private String problem(String text) {
        LocalDateTime time = ActivityData.parse(text);
        if (time == null) {
            return text.matches("\\d{4}-\\d{2}-\\d{2}.*") ? "日期 " + text.substring(0, 10) + " 不存在"
                    : "发送时间还差具体的日期或时刻";
        }
        LocalDateTime now = ActivityData.now(clock);
        if (!time.isAfter(now)) {
            return "发送时间 " + ActivityData.display(text) + " 已经过去了，需要晚于现在（北京时间 "
                    + now.format(ActivityData.DISPLAY_FORMAT) + "）";
        }
        return null;
    }

    @Override public String describe(Map<String, Object> slots) {
        Object time = slots.get("sendTime");
        return time == null ? "（暂无）" : "发送时间：" + ActivityData.display(time) + "（北京时间）";
    }

    @Override protected String askItem(String label, TaskFrame task) {
        return "具体的发送日期和时刻（北京时间，需要晚于现在）";
    }
}

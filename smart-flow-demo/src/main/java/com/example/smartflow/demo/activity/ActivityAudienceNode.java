package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.slot.SlotErrors;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import com.example.smartflow.node.slot.Slots;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 短信活动的发送人群：性别、年龄下限、年龄上限属于同一组"发送人群"，给出任意一项就算确定了；
 * 没限制的维度按不限处理。"全部人群"清掉旧的性别和年龄条件。
 */
@Component
public class ActivityAudienceNode extends SlotFillingNode {
    private static final String GROUP = "发送人群";

    public ActivityAudienceNode(NodeSupport support) { super(support); }

    @Override public String id() { return MinshengNames.ACTIVITY_AUDIENCE; }
    @Override protected String stageGoal() { return "收集短信发送人群（全部人群，或按性别、年龄限定）"; }
    @Override protected String nextNode() { return MinshengNames.ACTIVITY_CREATE; }

    @Override protected List<SlotSpec> slots() {
        return List.of(
                SlotSpec.bool("allAudience", "全部人群").extractOnly()
                        .describe("用户明确说全部人群、所有人、不限时为 true；只说“男性”或“20岁以上”时为 null").build(),
                SlotSpec.enumOf("gender", "性别").group(GROUP)
                        .option("MALE", "男性").option("FEMALE", "女性").option("ALL", "不限性别")
                        .describe("没提性别为 null").build(),
                SlotSpec.integer("ageMin", "年龄下限").range(0, 120).group(GROUP)
                        .describe("“20岁以上”填 20，“大于20岁”填 21").build(),
                SlotSpec.integer("ageMax", "年龄上限").range(0, 120).group(GROUP)
                        .describe("“60岁以下”填 60").build(),
                SlotSpec.bool("ageOpenEnded", "单边年龄范围").extractOnly()
                        .describe("只给了一端年龄时：用户说的是“20岁以上”“60岁以下”这类单边范围为 true（另一端不限）；"
                                + "只是调整一端（“上限改成70”“下限改为30岁”）为 false；没提年龄为 null").build());
    }

    @Override protected void normalize(Map<String, Object> extracted) {
        if (Slots.isTrue(extracted.get("allAudience"))) {
            extracted.put("gender", "ALL");
            extracted.put("ageMin", Slots.CLEAR);
            extracted.put("ageMax", Slots.CLEAR);
            return;
        }
        // 两端都给了自然整体替换；只给一端时，单边范围清掉另一端，调整一端则保留另一端
        boolean hasMin = extracted.get("ageMin") != null, hasMax = extracted.get("ageMax") != null;
        if (hasMin != hasMax && Slots.isTrue(extracted.get("ageOpenEnded"))) {
            extracted.put(hasMin ? "ageMax" : "ageMin", Slots.CLEAR);
        }
    }

    @Override protected void validateTogether(Map<String, Object> candidate, SlotErrors errors) {
        if (errors.isRejected("ageMin") || errors.isRejected("ageMax")) return;
        if (candidate.get("ageMin") instanceof Number min && candidate.get("ageMax") instanceof Number max
                && min.intValue() > max.intValue()) {
            // 人群条件整体不生效，保留原来的条件
            errors.reject("年龄下限（" + min + "岁）不能大于上限（" + max + "岁）", "ageMin", "ageMax", "gender");
        }
    }

    @Override public String describe(Map<String, Object> slots) {
        return missing(slots).isEmpty() ? "发送人群：" + ActivityData.audience(slots) : "（暂无）";
    }

    @Override protected String askItem(String label, TaskFrame task) {
        return "发送人群（全部人群，或按性别、年龄限定，例如20岁以上男性）";
    }
}

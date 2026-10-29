package com.example.smartflow.demo.node;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.node.slot.SlotErrors;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import com.example.smartflow.node.slot.Slots;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C1：活动创建 - 收集目标人群（年龄、性别、区域）。活动创建任务的入口。
 * <p>
 * 基于 {@link SlotFillingNode}：只声明槽位，抽取 / 校验 / 追问 / 推进由基类完成。
 * 定制了两处：
 * <ul>
 *   <li>{@link #normalize}："年龄不限"换算成 0～120；只说了下限或上限时，另一端清空（"60 岁以上"不应保留之前的上限）</li>
 *   <li>{@link #validateTogether}：下限不能大于上限；下限、上限一起改，任何一个不合法两个都不写入</li>
 * </ul>
 */
@Component
public class CollectTargetNode extends SlotFillingNode {

    public static final String SLOT_AGE_MIN = "ageMin";
    public static final String SLOT_AGE_MAX = "ageMax";
    public static final String SLOT_GENDER = "gender";
    public static final String SLOT_REGION = "region";
    /** 只抽取不保存：用户明确说年龄不限 */
    private static final String AGE_UNLIMITED = "ageUnlimited";

    private static final int AGE_LOWEST = 0;
    private static final int AGE_HIGHEST = 120;

    private static final List<SlotSpec> SLOTS = List.of(
            SlotSpec.integer(SLOT_AGE_MIN, "年龄下限").range(AGE_LOWEST, AGE_HIGHEST).group("年龄范围")
                    .describe("\"60岁以上\" → ageMin=60, ageMax=null；\"18到35岁\" → ageMin=18, ageMax=35。"
                            + "常见说法参考：老年人=60岁以上；青年=18到35岁；儿童=0到12岁")
                    .build(),
            SlotSpec.integer(SLOT_AGE_MAX, "年龄上限").range(AGE_LOWEST, AGE_HIGHEST).group("年龄范围").build(),
            SlotSpec.bool(AGE_UNLIMITED, "年龄不限").describe("用户明确说年龄不限时为 true，否则为 null").extractOnly().build(),
            SlotSpec.enumOf(SLOT_GENDER, "性别")
                    .option("MALE", "男性").option("FEMALE", "女性").option("ALL", "不限性别").build(),
            SlotSpec.string(SLOT_REGION, "目标区域或社区").maxLength(50).describe("保留用户原话").build());

    public CollectTargetNode(NodeSupport support) {
        super(support);
    }

    @Override
    public String id() {
        return MinshengNames.COLLECT_TARGET;
    }

    /** 活动创建任务的入口：路由识别到"活动创建"时开启任务，从这个节点开始 */
    @Override
    public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.taskEntry(FlowNames.ROUTER, MinshengNames.TASK_ACTIVITY_CREATE, "活动创建",
                "用户想创建、发起、策划一个社区/民生活动",
                List.of("我想办一个活动", "帮我策划一场敬老活动", "社区要搞个亲子活动"),
                10));
    }

    @Override
    protected List<SlotSpec> slots() {
        return SLOTS;
    }

    @Override
    protected String taskContext() {
        return "用户正在创建一个民生活动。";
    }

    @Override
    protected String stageGoal() {
        return "收集活动的目标人群（年龄范围、性别、目标区域或社区）";
    }

    @Override
    protected String nextNode() {
        return MinshengNames.CHOOSE_TEMPLATE;
    }

    // ------------------------------------------------------------------ 定制

    @Override
    protected void normalize(Map<String, Object> extracted) {
        Object unlimited = extracted.remove(AGE_UNLIMITED);
        if (Slots.isTrue(unlimited)) {
            extracted.put(SLOT_AGE_MIN, AGE_LOWEST);
            extracted.put(SLOT_AGE_MAX, AGE_HIGHEST);
        } else if (extracted.get(SLOT_AGE_MIN) != null || extracted.get(SLOT_AGE_MAX) != null) {
            // 年龄范围整体替换：只说了一端时，另一端清空
            extracted.putIfAbsent(SLOT_AGE_MIN, null);
            extracted.putIfAbsent(SLOT_AGE_MAX, null);
            extracted.replaceAll((k, v) -> (k.equals(SLOT_AGE_MIN) || k.equals(SLOT_AGE_MAX)) && v == null ? Slots.CLEAR : v);
        }
    }

    @Override
    protected void validateTogether(Map<String, Object> candidate, SlotErrors errors) {
        if (errors.isRejected(SLOT_AGE_MIN) || errors.isRejected(SLOT_AGE_MAX)) {
            // 一端不合法，另一端也不写入，避免出现半新半旧的年龄范围
            errors.reject(null, SLOT_AGE_MIN, SLOT_AGE_MAX);
        } else if (candidate.get(SLOT_AGE_MIN) instanceof Integer min && candidate.get(SLOT_AGE_MAX) instanceof Integer max
                && min > max) {
            errors.reject("年龄下限不能大于上限", SLOT_AGE_MIN, SLOT_AGE_MAX);
        }
    }

    /** 例如"60 岁及以上、女性、阳光社区" */
    @Override
    public String describe(Map<String, Object> slots) {
        List<String> parts = new ArrayList<>();
        Object min = slots.get(SLOT_AGE_MIN);
        Object max = slots.get(SLOT_AGE_MAX);
        if (min != null || max != null) {
            if (Integer.valueOf(AGE_LOWEST).equals(min) && Integer.valueOf(AGE_HIGHEST).equals(max)) {
                parts.add("年龄不限");
            } else if (min != null && max != null) {
                parts.add(min + "～" + max + " 岁");
            } else if (min != null) {
                parts.add(min + " 岁及以上");
            } else {
                parts.add(max + " 岁及以下");
            }
        }
        Object gender = slots.get(SLOT_GENDER);
        if (gender != null) {
            parts.add(switch (gender.toString()) {
                case "MALE" -> "男性";
                case "FEMALE" -> "女性";
                default -> "性别不限";
            });
        }
        Object region = slots.get(SLOT_REGION);
        if (region != null) {
            parts.add(region.toString());
        }
        return parts.isEmpty() ? "（暂无）" : String.join("、", parts);
    }

    @Override
    protected String completedText(Map<String, Object> slots) {
        return "好的，活动的目标人群确认为：" + describe(slots) + "。";
    }
}

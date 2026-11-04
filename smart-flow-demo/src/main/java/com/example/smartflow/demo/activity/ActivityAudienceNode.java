package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ActivityAudienceNode extends ActivityCollectionNode {
    public ActivityAudienceNode(NodeSupport support) { super(support); }
    @Override public String id() { return MinshengNames.ACTIVITY_AUDIENCE; }
    @Override public String section() { return "audience"; }
    @Override protected String label() { return "短信发送人群"; }
    @Override protected String question(TaskFrame task) { return "短信面向哪些人？可以是全部人群、男性、20岁以上，或组合条件。"; }
    @Override protected String askItem(TaskFrame task) { return "发送人群（全部人群，或按性别、年龄限定，例如20岁以上男性）"; }
    @Override protected String describeSaved(TaskFrame task) {
        return Boolean.TRUE.equals(task.getSlots().get("audienceConfirmed"))
                ? "发送人群：" + ActivityData.audience(task.getSlots()) : null;
    }
    @Override protected String collect(TaskFrame task, ActivityInput in, NodeScope scope) {
        if (!in.touches(section())) return null;
        Map<String, Object> s = new LinkedHashMap<>(task.getSlots());
        if (Boolean.TRUE.equals(in.allAudience())) {
            s.put("gender", "ALL");
            s.remove("ageMin"); s.remove("ageMax");
        } else {
            if (in.gender() != null) {
                String gender = in.gender().toUpperCase(java.util.Locale.ROOT);
                if (!List.of("MALE", "FEMALE", "ALL").contains(gender)) return "性别只能是男性、女性或不限";
                s.put("gender", gender);
            }
            s.putIfAbsent("gender", "ALL");
            if (in.ageMin() != null || in.ageMax() != null) {
                if (in.ageMin() != null && (in.ageMin() < 0 || in.ageMin() > 120)
                        || in.ageMax() != null && (in.ageMax() < 0 || in.ageMax() > 120)) {
                    return "年龄需要在0到120岁之间";
                }
                // 两端都给了，或者说的是"20岁以上"这类单边范围：整体替换；只调整一端（"上限改成70"）时保留另一端
                if (Boolean.TRUE.equals(in.ageOpenEnded()) || in.ageMin() != null && in.ageMax() != null) {
                    s.remove("ageMin"); s.remove("ageMax");
                }
                if (in.ageMin() != null) s.put("ageMin", in.ageMin());
                if (in.ageMax() != null) s.put("ageMax", in.ageMax());
                if (s.get("ageMin") instanceof Number min && s.get("ageMax") instanceof Number max
                        && min.intValue() > max.intValue()) {
                    return "年龄下限（" + min + "岁）不能大于上限（" + max + "岁）";
                }
            }
            if (Boolean.FALSE.equals(in.allAudience()) && in.gender() == null && in.ageMin() == null && in.ageMax() == null)
                return "还需要具体的人群条件，例如男性或20岁以上";
        }
        s.put("audienceConfirmed", true);
        task.setSlots(s);
        return null;
    }
}

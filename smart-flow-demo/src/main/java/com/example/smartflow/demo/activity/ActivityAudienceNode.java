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
    @Override protected String collect(TaskFrame task, ActivityInput in, NodeScope scope) {
        if (!in.touches(section())) return null;
        Map<String, Object> s = new LinkedHashMap<>(task.getSlots());
        if (Boolean.TRUE.equals(in.allAudience())) {
            s.put("gender", "ALL");
            s.remove("ageMin"); s.remove("ageMax");
        } else {
            if (in.gender() != null) {
                String gender = in.gender().toUpperCase(java.util.Locale.ROOT);
                if (!List.of("MALE", "FEMALE", "ALL").contains(gender)) return "性别可选男性、女性或不限。";
                s.put("gender", gender);
            }
            s.putIfAbsent("gender", "ALL");
            if (in.ageMin() != null || in.ageMax() != null) {
                s.remove("ageMin"); s.remove("ageMax");
                if (in.ageMin() != null) s.put("ageMin", in.ageMin());
                if (in.ageMax() != null) s.put("ageMax", in.ageMax());
            }
            if (in.ageMin() != null && (in.ageMin() < 0 || in.ageMin() > 120)
                    || in.ageMax() != null && (in.ageMax() < 0 || in.ageMax() > 120)
                    || in.ageMin() != null && in.ageMax() != null && in.ageMin() > in.ageMax()) {
                return "年龄须在0到120岁之间，且下限不能大于上限。";
            }
            if (Boolean.FALSE.equals(in.allAudience()) && in.gender() == null && in.ageMin() == null && in.ageMax() == null)
                return "请说明具体人群条件，例如男性或20岁以上。";
        }
        s.put("audienceConfirmed", true);
        task.setSlots(s);
        return null;
    }
}

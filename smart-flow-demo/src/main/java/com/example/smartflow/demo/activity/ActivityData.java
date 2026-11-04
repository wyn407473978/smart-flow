package com.example.smartflow.demo.activity;

import com.example.smartflow.flow.TaskFrame;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

/** 活动参数的代码校验；模型只做语言理解，不能决定是否允许提交。 */
public final class ActivityData {
    private ActivityData() { }
    public static final List<String> SECTIONS = List.of("region", "audience", "time", "template");
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    public static boolean future(Object value, Clock clock) {
        if (!(value instanceof String text)) return false;
        try {
            return text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                    && LocalDateTime.parse(text, TIME_FORMAT).isAfter(LocalDateTime.now(clock.withZone(ZONE)));
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    public static boolean filled(String section, TaskFrame task, Clock clock) {
        Map<String, Object> s = task.getSlots();
        if (task.getEditingSlots().contains(section)) return false;
        return switch (section) {
            case "region" -> s.get("region") instanceof String r && !r.isBlank() && r.length() <= 100;
            case "audience" -> Boolean.TRUE.equals(s.get("audienceConfirmed"));
            case "time" -> future(s.get("sendTime"), clock);
            case "template" -> s.get("smsContent") instanceof String t && !t.isBlank()
                    && candidates(s).contains(t);
            default -> false;
        };
    }

    public static boolean ready(TaskFrame task, Clock clock) {
        return SECTIONS.stream().allMatch(s -> filled(s, task, clock));
    }

    public static List<String> candidates(Map<String, Object> slots) {
        Object value = slots.get("templateCandidates");
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    public static String audience(Map<String, Object> slots) {
        if (!Boolean.TRUE.equals(slots.get("audienceConfirmed"))) return "（未确定）";
        String gender = switch (String.valueOf(slots.get("gender"))) {
            case "MALE" -> "男性";
            case "FEMALE" -> "女性";
            default -> "全部性别";
        };
        Object min = slots.get("ageMin"), max = slots.get("ageMax");
        String age = min != null && max != null ? min + "～" + max + "岁"
                : min != null ? min + "岁及以上" : max != null ? max + "岁及以下" : "全部年龄";
        return "ALL".equals(slots.get("gender")) && min == null && max == null ? "全部人群" : gender + "，" + age;
    }

    public static String summary(TaskFrame task) {
        Map<String, Object> s = task.getSlots();
        return "- 发送区域：" + s.getOrDefault("region", "（未填写）")
                + "\n- 发送人群：" + audience(s)
                + "\n- 发送时间（北京时间）：" + s.getOrDefault("sendTime", "（未填写）")
                + "\n- 短信内容：" + s.getOrDefault("smsContent", "（未选定）");
    }
}

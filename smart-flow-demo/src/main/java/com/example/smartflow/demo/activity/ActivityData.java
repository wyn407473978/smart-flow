package com.example.smartflow.demo.activity;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

/** 短信活动参数的公共工具：时间解析、人群描述、确认汇总。是否收齐由各收集节点的声明和规则决定。 */
public final class ActivityData {
    private ActivityData() { }

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    public static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    /** 严格解析 yyyy-MM-dd HH:mm:ss；格式不对或日期不存在返回 null */
    public static LocalDateTime parse(Object value) {
        if (!(value instanceof String text) || !text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) return null;
        try {
            return LocalDateTime.parse(text, TIME_FORMAT);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** 给用户看的时间，例如 2026-11-05 09:00 */
    public static String display(Object value) {
        LocalDateTime time = parse(value);
        return time == null ? String.valueOf(value) : time.format(DISPLAY_FORMAT);
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock.withZone(ZONE));
    }

    public static boolean future(Object value, Clock clock) {
        LocalDateTime time = parse(value);
        return time != null && time.isAfter(now(clock));
    }

    public static List<String> candidates(Map<String, Object> slots) {
        Object value = slots.get("templateCandidates");
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    /** 人群描述：没指定性别按不限处理；性别不限且没有年龄条件就是全部人群 */
    public static String audience(Map<String, Object> slots) {
        Object gender = slots.get("gender"), min = slots.get("ageMin"), max = slots.get("ageMax");
        if (gender == null && min == null && max == null) return "（未确定）";
        if ((gender == null || "ALL".equals(gender)) && min == null && max == null) return "全部人群";
        String genderText = switch (String.valueOf(gender)) {
            case "MALE" -> "男性";
            case "FEMALE" -> "女性";
            default -> "全部性别";
        };
        String age = min != null && max != null ? min + "～" + max + "岁"
                : min != null ? min + "岁及以上" : max != null ? max + "岁及以下" : "全部年龄";
        return genderText + "，" + age;
    }

    /** 确认时展示的完整参数 */
    public static String summary(Map<String, Object> s) {
        return "- 发送区域：" + s.getOrDefault("region", "（未填写）")
                + "\n- 发送人群：" + audience(s)
                + "\n- 发送时间（北京时间）：" + (s.get("sendTime") == null ? "（未填写）" : display(s.get("sendTime")))
                + "\n- 短信内容：" + s.getOrDefault("smsContent", "（未选定）");
    }
}

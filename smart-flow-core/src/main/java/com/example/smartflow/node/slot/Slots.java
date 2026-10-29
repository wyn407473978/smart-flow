package com.example.smartflow.node.slot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 槽位的纯逻辑：类型转换、校验、合并、"还缺什么"、描述文本、抽取提示词的字段说明。
 * 不涉及模型调用，全部可以单测。
 */
public final class Slots {

    private Slots() {
    }

    /**
     * 放进抽取结果里表示"清空这个槽位"。模型输出的 null 表示"没提到"，不会清空已有的值；
     * 需要清空时由节点的 normalize 放这个标记（例如用户把"18 到 35 岁"改成"60 岁以上"，上限要清掉）。
     */
    public static final Object CLEAR = new Object() {
        @Override
        public String toString() {
            return "CLEAR";
        }
    };

    /** 合并结果 */
    public record MergeResult(boolean changed, SlotErrors errors) {
    }

    // ------------------------------------------------------------------ 合并

    /**
     * 把一轮的抽取结果校验后合并进槽位（原地修改 slots）。
     * <ol>
     *   <li>逐个槽位转换类型、检查范围 / 长度 / 可选值，不合法的记错误、不写入</li>
     *   <li>得到候选结果后调用 crossCheck 做跨字段校验（例如下限不能大于上限），被拒绝的槽位回到本轮之前的值</li>
     * </ol>
     *
     * @param extracted  模型抽取结果（已经过节点的 normalize），值为 null 表示没提到，{@link #CLEAR} 表示清空
     * @param crossCheck 跨字段校验，可以为 null
     */
    public static MergeResult merge(List<SlotSpec> specs, Map<String, Object> slots, Map<String, Object> extracted,
                                    BiConsumer<Map<String, Object>, SlotErrors> crossCheck) {
        Map<String, Object> before = new LinkedHashMap<>(slots);
        Map<String, Object> candidate = new LinkedHashMap<>(slots);
        SlotErrors errors = new SlotErrors();

        for (SlotSpec spec : specs) {
            if (spec.extractOnly() || extracted == null || !extracted.containsKey(spec.name())) {
                continue;
            }
            Object raw = extracted.get(spec.name());
            if (raw == null) {
                continue;
            }
            if (raw == CLEAR) {
                candidate.remove(spec.name());
                continue;
            }
            Converted c = convert(spec, raw);
            if (c.error() != null) {
                errors.reject(c.error(), spec.name());
            } else if (c.value() != null) {
                candidate.put(spec.name(), c.value());
            }
        }

        if (crossCheck != null) {
            crossCheck.accept(candidate, errors);
        }
        for (String name : errors.rejectedSlots()) {
            if (before.containsKey(name)) {
                candidate.put(name, before.get(name));
            } else {
                candidate.remove(name);
            }
        }

        // 原地更新，保留已有 key 的顺序
        slots.keySet().retainAll(candidate.keySet());
        slots.putAll(candidate);
        return new MergeResult(!before.equals(slots), errors);
    }

    // ------------------------------------------------------------------ 类型转换

    /** 转换结果：value 和 error 至多一个非空；两个都为空表示"等同于没提到"（例如空字符串） */
    public record Converted(Object value, String error) {
    }

    public static Converted convert(SlotSpec spec, Object raw) {
        return switch (spec.type()) {
            case STRING -> toText(spec, raw);
            case INTEGER -> toInteger(spec, raw);
            case NUMBER -> toNumber(spec, raw);
            case BOOLEAN -> toBoolean(spec, raw);
            case ENUM -> toEnum(spec, raw);
        };
    }

    private static Converted toText(SlotSpec spec, Object raw) {
        String s = String.valueOf(raw).strip();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return new Converted(null, null);
        }
        if (spec.maxLength() != null && s.length() > spec.maxLength()) {
            return new Converted(null, spec.label() + "太长了，最多 " + spec.maxLength() + " 个字");
        }
        return new Converted(s, null);
    }

    private static Converted toInteger(SlotSpec spec, Object raw) {
        BigDecimal d = decimal(raw);
        if (d == null) {
            return new Converted(null, spec.label() + "需要是一个数字");
        }
        if (d.stripTrailingZeros().scale() > 0) {
            return new Converted(null, spec.label() + "需要是整数");
        }
        String rangeError = checkRange(spec, d.doubleValue());
        if (rangeError != null) {
            return new Converted(null, rangeError);
        }
        try {
            return new Converted(d.intValueExact(), null);
        } catch (ArithmeticException e) {
            return new Converted(null, spec.label() + "超出范围");
        }
    }

    private static Converted toNumber(SlotSpec spec, Object raw) {
        BigDecimal d = decimal(raw);
        if (d == null) {
            return new Converted(null, spec.label() + "需要是一个数字");
        }
        String rangeError = checkRange(spec, d.doubleValue());
        return rangeError != null ? new Converted(null, rangeError) : new Converted(d.doubleValue(), null);
    }

    private static Converted toBoolean(SlotSpec spec, Object raw) {
        if (raw instanceof Boolean b) {
            return new Converted(b, null);
        }
        String s = String.valueOf(raw).strip().toLowerCase();
        if (List.of("true", "是", "yes", "1").contains(s)) {
            return new Converted(true, null);
        }
        if (List.of("false", "否", "no", "0").contains(s)) {
            return new Converted(false, null);
        }
        return new Converted(null, spec.label() + "只能回答是或否");
    }

    private static Converted toEnum(SlotSpec spec, Object raw) {
        String s = String.valueOf(raw).strip();
        if (s.isEmpty()) {
            return new Converted(null, null);
        }
        for (Map.Entry<String, String> o : spec.options().entrySet()) {
            if (o.getKey().equalsIgnoreCase(s) || o.getValue().equals(s)) {
                return new Converted(o.getKey(), null);
            }
        }
        return new Converted(null, spec.label() + "只能是" + String.join("、", spec.options().values()) + "之一");
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;   // NaN / Infinity
            }
        }
        try {
            return new BigDecimal(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String checkRange(SlotSpec spec, double v) {
        boolean tooSmall = spec.min() != null && v < spec.min();
        boolean tooBig = spec.max() != null && v > spec.max();
        if (!tooSmall && !tooBig) {
            return null;
        }
        if (spec.min() != null && spec.max() != null) {
            return spec.label() + "需要在 " + fmt(spec.min()) + " 到 " + fmt(spec.max()) + " 之间";
        }
        return tooSmall ? spec.label() + "不能小于 " + fmt(spec.min()) : spec.label() + "不能大于 " + fmt(spec.max());
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------ 收集进度

    /** 还缺的必填项（分组的说组名），按声明顺序 */
    public static List<String> missing(List<SlotSpec> specs, Map<String, Object> slots) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> satisfiedGroups = new LinkedHashSet<>();
        for (SlotSpec spec : specs) {
            if (spec.group() != null && filled(slots, spec.name())) {
                satisfiedGroups.add(spec.group());
            }
        }
        for (SlotSpec spec : specs) {
            if (!spec.required() || filled(slots, spec.name())) {
                continue;
            }
            if (spec.group() != null && satisfiedGroups.contains(spec.group())) {
                continue;
            }
            out.add(spec.askLabel());
        }
        return new ArrayList<>(out);
    }

    private static boolean filled(Map<String, Object> slots, String name) {
        Object v = slots.get(name);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    /** 默认的描述文本："性别：女性、目标区域或社区：阳光社区"；什么都没有时是"（暂无）" */
    public static String describe(List<SlotSpec> specs, Map<String, Object> slots) {
        List<String> parts = new ArrayList<>();
        for (SlotSpec spec : specs) {
            if (spec.extractOnly() || !filled(slots, spec.name())) {
                continue;
            }
            parts.add(spec.label() + "：" + display(spec, slots.get(spec.name())));
        }
        return parts.isEmpty() ? "（暂无）" : String.join("、", parts);
    }

    /** 单个值的展示文本：枚举显示中文名，布尔显示是 / 否 */
    public static String display(SlotSpec spec, Object value) {
        if (value == null) {
            return "";
        }
        return switch (spec.type()) {
            case ENUM -> spec.options().getOrDefault(String.valueOf(value), String.valueOf(value));
            case BOOLEAN -> Boolean.TRUE.equals(value) ? "是" : "否";
            case NUMBER -> value instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(value);
            default -> String.valueOf(value);
        };
    }

    // ------------------------------------------------------------------ 抽取提示词

    /** 抽取提示词里的字段说明，一个字段一行 */
    public static String fieldGuide(List<SlotSpec> specs) {
        return specs.stream().map(Slots::fieldLine).collect(Collectors.joining("\n"));
    }

    private static String fieldLine(SlotSpec spec) {
        StringBuilder sb = new StringBuilder("- ").append(spec.name()).append("（").append(spec.label()).append("）：");
        switch (spec.type()) {
            case STRING -> sb.append("文本").append(spec.maxLength() == null ? "" : "，不超过 " + spec.maxLength() + " 字");
            case INTEGER -> sb.append("整数").append(rangeText(spec));
            case NUMBER -> sb.append("数字").append(rangeText(spec));
            case BOOLEAN -> sb.append("true / false");
            case ENUM -> sb.append("只能是 ").append(spec.options().entrySet().stream()
                    .map(e -> e.getKey() + "（" + e.getValue() + "）").collect(Collectors.joining(" / ")));
        }
        if (!spec.description().isEmpty()) {
            sb.append("。").append(spec.description());
        }
        return sb.toString();
    }

    private static String rangeText(SlotSpec spec) {
        if (spec.min() != null && spec.max() != null) {
            return "，范围 " + fmt(spec.min()) + "～" + fmt(spec.max());
        }
        if (spec.min() != null) {
            return "，不小于 " + fmt(spec.min());
        }
        return spec.max() != null ? "，不大于 " + fmt(spec.max()) : "";
    }

    /** 抽取提示词里的 JSON 格式示例：所有字段都是 null */
    public static String jsonTemplate(List<SlotSpec> specs) {
        return specs.stream().map(s -> "\"" + s.name() + "\": null").collect(Collectors.joining(", ", "{", "}"));
    }

    /** 追问时"还缺少"的说明：枚举项带上可选值，方便模型告诉用户有哪些选择 */
    public static List<String> missingWithHints(List<SlotSpec> specs, List<String> missing) {
        List<String> out = new ArrayList<>();
        for (String label : missing) {
            String hint = specs.stream()
                    .filter(s -> s.type() == SlotType.ENUM && label.equals(s.askLabel()))
                    .findFirst()
                    .map(s -> "（可选：" + String.join("、", s.options().values()) + "）")
                    .orElse("");
            out.add(label + hint);
        }
        return out;
    }

    /** 模型输出的布尔值可能是 true、"true"、"是" */
    public static boolean isTrue(Object v) {
        return v instanceof Boolean b ? b : v != null && List.of("true", "是", "yes").contains(String.valueOf(v).strip().toLowerCase());
    }
}

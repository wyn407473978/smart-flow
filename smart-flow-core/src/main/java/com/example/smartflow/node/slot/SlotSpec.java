package com.example.smartflow.node.slot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个槽位（要向用户收集的一项信息）的声明。抽取提示词、类型转换、校验、"还缺什么"都由它生成：
 *
 * <pre>
 * SlotSpec.integer("ageMin", "年龄下限").range(0, 120).group("年龄范围")
 *         .describe("\"60岁以上\" → ageMin=60").build();
 * SlotSpec.enumOf("gender", "性别").option("MALE", "男性").option("FEMALE", "女性").build();
 * SlotSpec.string("region", "目标区域或社区").maxLength(50).build();
 * </pre>
 *
 * @param name        槽位名，也是模型输出 JSON 的字段名、TaskFrame.slots 的 key，建议小驼峰
 * @param label       中文名，用于提示词、追问、确认文案
 * @param type        取值类型
 * @param description 给抽取模型的补充说明（取值规则、常见说法），可以为空
 * @param required    是否必填。必填项没收齐之前，节点会一直追问
 * @param group       必填组：同一组里的槽位填了任意一个就算这一组收齐了（例如年龄下限 / 上限 → "年龄范围"），
 *                    追问时说的是组名。为空表示不分组
 * @param options     ENUM 的可选值：编码 → 中文名，按声明顺序
 * @param min         INTEGER / NUMBER 的最小值（含），为空不限制
 * @param max         INTEGER / NUMBER 的最大值（含），为空不限制
 * @param maxLength   STRING 的最大长度，为空不限制
 * @param extractOnly 只让模型抽取、不保存的辅助字段（例如"年龄不限"），由节点的 normalize 换算成真正的槽位
 */
public record SlotSpec(String name,
                       String label,
                       SlotType type,
                       String description,
                       boolean required,
                       String group,
                       Map<String, String> options,
                       Double min,
                       Double max,
                       Integer maxLength,
                       boolean extractOnly) {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");

    public SlotSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("槽位名只能由字母、数字、下划线组成，且以字母开头: " + name);
        }
        description = description == null ? "" : description.strip();
        group = group == null || group.isBlank() ? null : group.strip();
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        if (type == SlotType.ENUM && options.isEmpty()) {
            throw new IllegalArgumentException("ENUM 槽位 " + name + " 至少要有一个可选值");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("槽位 " + name + " 的 min 不能大于 max");
        }
        if (extractOnly) {
            required = false;
            group = null;
        }
    }

    // ------------------------------------------------------------------ 工厂方法

    public static Builder string(String name, String label) {
        return new Builder(name, label, SlotType.STRING);
    }

    public static Builder integer(String name, String label) {
        return new Builder(name, label, SlotType.INTEGER);
    }

    public static Builder number(String name, String label) {
        return new Builder(name, label, SlotType.NUMBER);
    }

    public static Builder bool(String name, String label) {
        return new Builder(name, label, SlotType.BOOLEAN);
    }

    public static Builder enumOf(String name, String label) {
        return new Builder(name, label, SlotType.ENUM);
    }

    /** 追问时用的名字：分组的槽位说组名 */
    public String askLabel() {
        return group != null ? group : label;
    }

    /** 默认必填；可选项用 {@link #optional()}；"只抽取不保存"的辅助字段用 {@link #extractOnly()} */
    public static final class Builder {
        private final String name;
        private final String label;
        private final SlotType type;
        private String description;
        private boolean required = true;
        private String group;
        private final Map<String, String> options = new LinkedHashMap<>();
        private Double min;
        private Double max;
        private Integer maxLength;
        private boolean extractOnly;

        private Builder(String name, String label, SlotType type) {
            this.name = name;
            this.label = label;
            this.type = type;
        }

        public Builder describe(String description) {
            this.description = description;
            return this;
        }

        public Builder optional() {
            this.required = false;
            return this;
        }

        public Builder group(String group) {
            this.group = group;
            return this;
        }

        public Builder option(String code, String label) {
            this.options.put(code, label);
            return this;
        }

        public Builder range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Builder min(double min) {
            this.min = min;
            return this;
        }

        public Builder max(double max) {
            this.max = max;
            return this;
        }

        public Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder extractOnly() {
            this.extractOnly = true;
            return this;
        }

        public SlotSpec build() {
            return new SlotSpec(name, label, type, description, required, group, options, min, max, maxLength, extractOnly);
        }
    }
}

package com.example.smartflow.node.slot;

import com.example.smartflow.node.slot.Slots.MergeResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位的转换、校验、合并都是纯代码，这一层不需要模型。
 */
class SlotsTest {

    private static final SlotSpec AGE_MIN = SlotSpec.integer("ageMin", "年龄下限").range(0, 120).group("年龄范围").build();
    private static final SlotSpec AGE_MAX = SlotSpec.integer("ageMax", "年龄上限").range(0, 120).group("年龄范围").build();
    private static final SlotSpec GENDER = SlotSpec.enumOf("gender", "性别")
            .option("MALE", "男性").option("FEMALE", "女性").option("ALL", "不限性别").build();
    private static final SlotSpec REGION = SlotSpec.string("region", "目标区域").maxLength(5).build();
    private static final SlotSpec NOTE = SlotSpec.string("note", "备注").optional().build();
    private static final SlotSpec BUDGET = SlotSpec.number("budget", "预算").min(0).optional().build();
    private static final SlotSpec ONLINE = SlotSpec.bool("online", "线上活动").optional().build();
    private static final List<SlotSpec> SPECS = List.of(AGE_MIN, AGE_MAX, GENDER, REGION, NOTE, BUDGET, ONLINE);

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ 类型转换

    @Test
    void integerAcceptsNumbersAndNumericText() {
        assertEquals(60, Slots.convert(AGE_MIN, 60).value());
        assertEquals(60, Slots.convert(AGE_MIN, 60.0).value());
        assertEquals(60, Slots.convert(AGE_MIN, " 60 ").value());
        assertEquals("年龄下限需要是整数", Slots.convert(AGE_MIN, 60.5).error());
        assertEquals("年龄下限需要是一个数字", Slots.convert(AGE_MIN, "六十").error());
        assertEquals("年龄下限需要在 0 到 120 之间", Slots.convert(AGE_MIN, 150).error());
    }

    @Test
    void enumMatchesCodeOrLabel() {
        assertEquals("FEMALE", Slots.convert(GENDER, "female").value());
        assertEquals("FEMALE", Slots.convert(GENDER, "女性").value());
        assertEquals("性别只能是男性、女性、不限性别之一", Slots.convert(GENDER, "未知").error());
    }

    @Test
    void textAndBooleanAndNumber() {
        assertNull(Slots.convert(REGION, "   ").value(), "空白文本等同于没提到");
        assertNull(Slots.convert(REGION, "   ").error());
        assertEquals("目标区域太长了，最多 5 个字", Slots.convert(REGION, "非常非常长的社区名").error());
        assertEquals(true, Slots.convert(ONLINE, "是").value());
        assertEquals(false, Slots.convert(ONLINE, false).value());
        assertEquals(12.5, Slots.convert(BUDGET, "12.5").value());
        assertEquals("预算不能小于 0", Slots.convert(BUDGET, -1).error());
    }

    // ------------------------------------------------------------------ 合并

    @Test
    void mergeWritesValidValuesAndReportsChange() {
        Map<String, Object> slots = new LinkedHashMap<>();
        MergeResult r = Slots.merge(SPECS, slots, map("ageMin", 60, "gender", "female", "region", null), null);

        assertTrue(r.changed());
        assertTrue(r.errors().isEmpty());
        assertEquals(map("ageMin", 60, "gender", "FEMALE"), slots);
    }

    @Test
    void nullMeansNotMentionedButClearRemoves() {
        Map<String, Object> slots = new LinkedHashMap<>(map("ageMin", 18, "ageMax", 35));
        Slots.merge(SPECS, slots, map("ageMin", null, "ageMax", null), null);
        assertEquals(map("ageMin", 18, "ageMax", 35), slots, "null 不清空已有值");

        Slots.merge(SPECS, slots, map("ageMin", 60, "ageMax", Slots.CLEAR), null);
        assertEquals(map("ageMin", 60), slots);
    }

    @Test
    void invalidValueKeepsPreviousValue() {
        Map<String, Object> slots = new LinkedHashMap<>(map("gender", "MALE"));
        MergeResult r = Slots.merge(SPECS, slots, map("gender", "外星人"), null);

        assertFalse(r.changed());
        assertEquals("MALE", slots.get("gender"));
        assertEquals(List.of("性别只能是男性、女性、不限性别之一"), r.errors().messages());
    }

    @Test
    void crossCheckRevertsRejectedSlots() {
        Map<String, Object> slots = new LinkedHashMap<>(map("ageMin", 10));
        MergeResult r = Slots.merge(SPECS, slots, map("ageMin", 50, "ageMax", 30, "gender", "ALL"), (c, errors) -> {
            if (c.get("ageMin") instanceof Integer min && c.get("ageMax") instanceof Integer max && min > max) {
                errors.reject("年龄下限不能大于上限", "ageMin", "ageMax");
            }
        });

        assertEquals(map("ageMin", 10, "gender", "ALL"), slots, "年龄回到本轮之前，性别照常写入");
        assertEquals(List.of("年龄下限不能大于上限"), r.errors().messages());
        assertTrue(r.changed());
    }

    @Test
    void extractOnlyFieldsAreNeverStored() {
        SlotSpec unlimited = SlotSpec.bool("ageUnlimited", "年龄不限").extractOnly().build();
        Map<String, Object> slots = new HashMap<>();
        Slots.merge(List.of(unlimited, AGE_MIN), slots, map("ageUnlimited", true), null);
        assertTrue(slots.isEmpty());
        assertFalse(unlimited.required(), "辅助字段不会是必填");
    }

    // ------------------------------------------------------------------ 收集进度与描述

    @Test
    void missingUsesGroupLabelAndSkipsOptional() {
        assertEquals(List.of("年龄范围", "性别", "目标区域"), Slots.missing(SPECS, map()));
        assertEquals(List.of("性别", "目标区域"), Slots.missing(SPECS, map("ageMax", 35)), "组里填了一个就算收齐");
        assertEquals(List.of(), Slots.missing(SPECS, map("ageMin", 60, "gender", "ALL", "region", "阳光")));
        assertEquals(List.of("目标区域"), Slots.missing(SPECS, map("ageMin", 60, "gender", "ALL", "region", " ")),
                "空白文本不算填了");
    }

    @Test
    void describeShowsLabelsInDeclarationOrder() {
        assertEquals("（暂无）", Slots.describe(SPECS, map()));
        assertEquals("年龄下限：60、性别：女性、预算：100、线上活动：否",
                Slots.describe(SPECS, map("online", false, "budget", 100.0, "gender", "FEMALE", "ageMin", 60)));
        assertEquals(List.of("年龄范围", "性别（可选：男性、女性、不限性别）"),
                Slots.missingWithHints(SPECS, List.of("年龄范围", "性别")));
    }

    @Test
    void promptPartsAreGeneratedFromSpecs() {
        String guide = Slots.fieldGuide(List.of(AGE_MIN, GENDER, SlotSpec.string("region", "区域").describe("保留用户原话").build()));
        assertEquals("""
                - ageMin（年龄下限）：整数，范围 0～120
                - gender（性别）：只能是 MALE（男性） / FEMALE（女性） / ALL（不限性别）
                - region（区域）：文本。保留用户原话""".strip(), guide);
        assertEquals("{\"ageMin\": null, \"gender\": null}", Slots.jsonTemplate(List.of(AGE_MIN, GENDER)));
    }

    @Test
    void invalidSpecsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> SlotSpec.enumOf("x", "X").build());
        assertThrows(IllegalArgumentException.class, () -> SlotSpec.integer("1x", "X").build());
        assertThrows(IllegalArgumentException.class, () -> SlotSpec.integer("x", "X").range(10, 1).build());
    }
}

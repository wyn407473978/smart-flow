package com.example.smartflow.demo.node;

import com.example.smartflow.node.slot.Slots.MergeResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位校验、合并逻辑是纯代码，不需要模型也能测。这类测试是 Evals 里最便宜的一层。
 */
class CollectTargetNodeTest {

    /** 只用到纯代码部分（applyExtraction / missing / describe），不需要 NodeSupport */
    private final CollectTargetNode node = new CollectTargetNode(null);

    /** 模拟模型抽取结果：没提到的字段是 null */
    private static Map<String, Object> extracted(Integer ageMin, Integer ageMax, Boolean ageUnlimited, String gender, String region) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ageMin", ageMin);
        m.put("ageMax", ageMax);
        m.put("ageUnlimited", ageUnlimited);
        m.put("gender", gender);
        m.put("region", region);
        return m;
    }

    @Test
    void mergeValidValues() {
        Map<String, Object> slots = new LinkedHashMap<>();

        MergeResult r = node.applyExtraction(slots, extracted(60, null, null, "female", "阳光社区"));

        assertTrue(r.changed());
        assertTrue(r.errors().isEmpty());
        assertEquals(60, slots.get(CollectTargetNode.SLOT_AGE_MIN));
        assertEquals("FEMALE", slots.get(CollectTargetNode.SLOT_GENDER));
        assertTrue(node.missing(slots).isEmpty());
        assertEquals("60 岁及以上、女性、阳光社区", node.describe(slots));
    }

    @Test
    void rejectInvalidAge() {
        Map<String, Object> slots = new LinkedHashMap<>();

        MergeResult r = node.applyExtraction(slots, extracted(50, 30, null, null, null));

        assertEquals(List.of("年龄下限不能大于上限"), r.errors().messages());
        assertFalse(slots.containsKey(CollectTargetNode.SLOT_AGE_MIN));
        assertTrue(node.missing(slots).contains("年龄范围"));
    }

    @Test
    void outOfRangeAgeKeepsPreviousRange() {
        Map<String, Object> slots = new LinkedHashMap<>(Map.of("ageMin", 18, "ageMax", 35));

        MergeResult r = node.applyExtraction(slots, extracted(150, null, null, null, null));

        assertEquals(List.of("年龄下限需要在 0 到 120 之间"), r.errors().messages());
        assertEquals(Map.of("ageMin", 18, "ageMax", 35), slots, "下限不合法时上限也不能被清掉");
    }

    @Test
    void newLowerBoundReplacesWholeRange() {
        Map<String, Object> slots = new LinkedHashMap<>(Map.of("ageMin", 18, "ageMax", 35));

        node.applyExtraction(slots, extracted(60, null, null, null, null));

        assertEquals(Map.of("ageMin", 60), slots, "\"60 岁以上\"不应保留之前的上限 35");
        assertEquals("60 岁及以上", node.describe(slots));
    }

    @Test
    void correctionOverwritesPreviousValue() {
        Map<String, Object> slots = new LinkedHashMap<>();
        node.applyExtraction(slots, extracted(null, null, null, "MALE", null));

        MergeResult r = node.applyExtraction(slots, extracted(null, null, null, "FEMALE", null));

        assertTrue(r.changed());
        assertEquals("FEMALE", slots.get(CollectTargetNode.SLOT_GENDER));
    }

    @Test
    void emptyExtractionChangesNothing() {
        Map<String, Object> slots = new LinkedHashMap<>(Map.of(CollectTargetNode.SLOT_GENDER, "ALL"));

        MergeResult r = node.applyExtraction(slots, extracted(null, null, null, null, null));

        assertFalse(r.changed());
        assertEquals(List.of("年龄范围", "目标区域或社区"), node.missing(slots));
    }

    @Test
    void ageUnlimited() {
        Map<String, Object> slots = new LinkedHashMap<>();
        node.applyExtraction(slots, extracted(null, null, true, "ALL", "全区"));

        assertEquals("年龄不限、性别不限、全区", node.describe(slots));
        assertFalse(slots.containsKey("ageUnlimited"), "辅助字段不保存");
    }
}

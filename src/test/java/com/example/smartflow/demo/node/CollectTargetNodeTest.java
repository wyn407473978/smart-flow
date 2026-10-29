package com.example.smartflow.demo.node;

import com.example.smartflow.demo.node.CollectTargetNode.Extraction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @Test
    void mergeValidValues() {
        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        boolean changed = CollectTargetNode.merge(slots,
                new Extraction(60, null, null, "female", "阳光社区"), errors);

        assertTrue(changed);
        assertTrue(errors.isEmpty());
        assertEquals(60, slots.get(CollectTargetNode.SLOT_AGE_MIN));
        assertEquals("FEMALE", slots.get(CollectTargetNode.SLOT_GENDER));
        assertTrue(CollectTargetNode.missingSlots(slots).isEmpty());
        assertEquals("60 岁及以上、女性、阳光社区", CollectTargetNode.describe(slots));
    }

    @Test
    void rejectInvalidAge() {
        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        CollectTargetNode.merge(slots, new Extraction(50, 30, null, null, null), errors);

        assertEquals(1, errors.size());
        assertFalse(slots.containsKey(CollectTargetNode.SLOT_AGE_MIN));
        assertTrue(CollectTargetNode.missingSlots(slots).contains("年龄范围"));
    }

    @Test
    void correctionOverwritesPreviousValue() {
        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CollectTargetNode.merge(slots, new Extraction(null, null, null, "MALE", null), errors);

        boolean changed = CollectTargetNode.merge(slots, new Extraction(null, null, null, "FEMALE", null), errors);

        assertTrue(changed);
        assertEquals("FEMALE", slots.get(CollectTargetNode.SLOT_GENDER));
    }

    @Test
    void emptyExtractionChangesNothing() {
        Map<String, Object> slots = new LinkedHashMap<>(Map.of(CollectTargetNode.SLOT_GENDER, "ALL"));
        List<String> errors = new ArrayList<>();

        boolean changed = CollectTargetNode.merge(slots, new Extraction(null, null, null, null, null), errors);

        assertFalse(changed);
        assertEquals(List.of("年龄范围", "目标区域或社区"), CollectTargetNode.missingSlots(slots));
    }

    @Test
    void ageUnlimited() {
        Map<String, Object> slots = new LinkedHashMap<>();
        CollectTargetNode.merge(slots, new Extraction(null, null, true, "ALL", "全区"), new ArrayList<>());

        assertEquals("年龄不限、性别不限、全区", CollectTargetNode.describe(slots));
    }
}

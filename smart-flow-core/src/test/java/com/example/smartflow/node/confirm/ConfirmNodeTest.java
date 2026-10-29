package com.example.smartflow.node.confirm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmNodeTest {

    /** 明确的确认不调用模型；带否定、带修改内容、只说"继续"的都要交给模型判断 */
    @Test
    void plainConfirmationDetection() {
        for (String yes : List.of("确认", "确认！", "好的。", "嗯，可以", "是的", "对的", "没问题", "可以了", "OK", " 提交 ")) {
            assertTrue(ConfirmNode.isPlainConfirmation(yes), yes);
        }
        for (String other : List.of("", "嗯", "不确认", "确认，但是性别改成男性", "继续", "好的，时间改到周日", "不对")) {
            assertFalse(ConfirmNode.isPlainConfirmation(other), other);
        }
    }
}

package com.example.smartflow.node.slot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一轮抽取里的校验错误。被拒绝的槽位保持本轮之前的值，错误信息会在追问时告诉用户。
 */
public final class SlotErrors {

    private final List<String> messages = new ArrayList<>();
    private final Set<String> rejected = new LinkedHashSet<>();

    /**
     * 拒绝本轮对这些槽位的修改。
     *
     * @param message 给用户看的原因（会让模型转述），为 null 时只拒绝不提示（例如同组的另一个值已经报过错）
     */
    public void reject(String message, String... slots) {
        if (message != null && !message.isBlank() && !messages.contains(message)) {
            messages.add(message);
        }
        rejected.addAll(List.of(slots));
    }

    public boolean isRejected(String slot) {
        return rejected.contains(slot);
    }

    public boolean isEmpty() {
        return messages.isEmpty() && rejected.isEmpty();
    }

    public List<String> messages() {
        return Collections.unmodifiableList(messages);
    }

    public Set<String> rejectedSlots() {
        return Collections.unmodifiableSet(rejected);
    }
}

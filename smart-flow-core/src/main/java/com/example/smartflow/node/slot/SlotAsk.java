package com.example.smartflow.node.slot;

import com.example.smartflow.flow.TaskFrame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 追问计划的公共部分：问哪几项、代码兜底文案、需要原样展示的候选。
 * 单个 {@link SlotFillingNode} 是只有一个成员的情况，{@link SlotGroupNode} 是多个成员的情况，规则一致：
 * <ol>
 *   <li>有需要重新提供的项（校验失败、用户要求修改、已保存的值失效）时，只问这些</li>
 *   <li>否则先问正在等用户从候选里选的项，再按成员和槽位的声明顺序补缺</li>
 *   <li>每轮最多问两项</li>
 * </ol>
 */
final class SlotAsk {

    private SlotAsk() {
    }

    static final String CHOICES_NOTE = "上面列出的候选已由系统原样展示给用户，不要复述、改写或评价，也不要替用户选择，请用户回复编号。";

    /**
     * @param node         成员节点
     * @param pending      还缺的项（含待修改、已失效的项），askLabel
     * @param corrections  其中需要用户重新提供的项
     * @param changed      本轮有没有变化（变化了才把候选当作"刚生成的"展示）
     */
    record Entry(SlotFillingNode node, List<String> pending, List<String> corrections, boolean changed) {
    }

    /**
     * @param choices      需要由代码原样展示的候选块
     * @param askItems     交给追问模型的"本轮要问"
     * @param later        之后还要了解的项
     * @param correcting   本轮问的是否是要重新提供的项
     * @param codeQuestion 代码兜底文案，也会记到任务的 awaiting
     */
    record Prepared(List<String> choices, List<String> askItems, List<String> later, boolean correcting,
                    String codeQuestion) {
    }

    private record Item(SlotFillingNode node, String label) {
    }

    static Prepared prepare(List<Entry> entries, TaskFrame task) {
        boolean correcting = entries.stream().anyMatch(e -> !e.corrections().isEmpty());
        List<Item> ordered = new ArrayList<>();
        if (correcting) {
            entries.forEach(e -> e.corrections().forEach(label -> ordered.add(new Item(e.node(), label))));
        } else {
            // 正在等用户从候选里选的项排在前面，候选刚展示过就请用户选
            entries.stream().filter(e -> hasChoices(e, task))
                    .forEach(e -> e.pending().forEach(label -> ordered.add(new Item(e.node(), label))));
            entries.stream().filter(e -> !hasChoices(e, task))
                    .forEach(e -> e.pending().forEach(label -> ordered.add(new Item(e.node(), label))));
        }
        List<Item> asking = distinct(ordered).stream().limit(2).toList();
        Set<String> askingLabels = new LinkedHashSet<>(asking.stream().map(Item::label).toList());

        List<String> later = new ArrayList<>();
        for (Entry e : entries) {
            for (String label : e.pending()) {
                if (!askingLabels.contains(label) && !later.contains(label)) {
                    later.add(label);
                }
            }
        }

        // 候选原文：本轮刚生成的，或者这一轮要用户选的
        List<String> choices = new ArrayList<>();
        for (Entry e : entries) {
            boolean asked = asking.stream().anyMatch(i -> i.node() == e.node());
            if (e.changed() || asked) {
                String text = e.node().choicesText(task, e.changed());
                if (text != null && !text.isBlank()) {
                    choices.add(text.strip());
                }
            }
        }

        List<String> askItems = asking.stream().map(i -> i.node().askItem(i.label(), task)).toList();
        String codeQuestion = askItems.isEmpty() ? "请根据上述提示重新提供信息。"
                : (correcting ? "请重新提供：" : "请补充：") + String.join("、", askItems) + "。";
        return new Prepared(choices, askItems, later, correcting, codeQuestion);
    }

    private static boolean hasChoices(Entry e, TaskFrame task) {
        return !e.pending().isEmpty() && e.node().choicesText(task, false) != null;
    }

    private static List<Item> distinct(List<Item> items) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Item item : items) {
            if (seen.add(item.label())) {
                out.add(item);
            }
        }
        return out;
    }
}

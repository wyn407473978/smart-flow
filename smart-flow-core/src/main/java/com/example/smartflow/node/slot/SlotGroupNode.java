package com.example.smartflow.node.slot;

import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.HistoryPolicy;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.TaskStackEdit;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一组没有先后顺序的收集节点（{@link SlotFillingNode}）。用户可以按任意顺序提供，一句话也可以同时提供多项：
 * <ol>
 *   <li>组节点用所有成员声明的槽位做一次抽取（只调用一次模型）</li>
 *   <li>本轮提到的成员各自用自己的声明和规则校验、合并（{@link SlotFillingNode#collect}），包括 afterMerge</li>
 *   <li>都收齐了就推进到 {@link #nextNode()}；没收齐就统一追问：代码先输出本轮记下的信息和校验提示，
 *       问哪几项由代码决定（规则同 {@link SlotFillingNode}），措辞交给追问模型</li>
 * </ol>
 * 任务停在组节点上，下一轮用户回答任意一项都会回到这里。用户要求修改某个成员的字段时，路由也会回到组节点。
 * 成员节点照常注册（提示词页签可以看到它们的声明），但不需要路由声明，也不会被单独路由到。
 *
 * <pre>
 * &#64;Component
 * public class CreateEventNode extends SlotGroupNode {
 *     public CreateEventNode(NodeSupport support, EventPlaceNode place, EventTimeNode time) {
 *         super(support, List.of(place, time));
 *     }
 *     &#64;Override public String id() { return "create_event"; }
 *     &#64;Override protected String stageGoal() { return "收集活动地点和时间，顺序不限"; }
 *     &#64;Override protected String nextNode() { return "confirm_event"; }
 * }
 * </pre>
 */
public abstract class SlotGroupNode extends AbstractNode {

    private static final String EXTRA_RULES = "4. 用户一句话可能同时提到多项信息，全部抽取，没有先后顺序；只说了其中一项时，其他字段输出 null。";

    private final List<SlotFillingNode> members;

    protected SlotGroupNode(NodeSupport support, List<? extends SlotFillingNode> members) {
        super(support);
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("SlotGroupNode 至少需要一个成员");
        }
        this.members = List.copyOf(members);
        Set<String> names = new HashSet<>();
        for (SlotFillingNode member : this.members) {
            for (SlotSpec spec : member.slots()) {
                if (!names.add(spec.name())) {
                    throw new IllegalArgumentException("组 " + getClass().getSimpleName() + " 的成员槽位重名：" + spec.name());
                }
            }
        }
    }

    /** 这一组在收集什么，例如"收集短信活动的区域、人群、时间和模版，顺序不限"。也会告诉路由模型 */
    protected abstract String stageGoal();

    /** 收齐后推进到哪个节点（通常是确认节点），本轮内会直接执行它 */
    protected abstract String nextNode();

    /** 用户正在办的事的背景，写进抽取和追问提示词，可以为空 */
    protected String taskContext() {
        return "";
    }

    /** 成员节点，按追问顺序 */
    public final List<SlotFillingNode> members() {
        return members;
    }

    /** 供路由目录沿任务阶段查找后续阶段 */
    public final String nextStage() {
        return nextNode();
    }

    /** 所有成员都收齐、没有待修改和失效的项。确认节点提交前可以用它复查 */
    public boolean complete(TaskFrame task) {
        return members.stream().allMatch(m -> m.pendingLabels(task).isEmpty());
    }

    /** 所有成员的已收集信息，例如"发送区域：…；发送人群：…" */
    public String describe(Map<String, Object> slots) {
        return members.stream().map(m -> m.describe(slots)).filter(d -> !d.equals("（暂无）"))
                .collect(Collectors.joining("；"));
    }

    @Override
    public NodeType type() {
        return NodeType.SLOT_FILLING;
    }

    @Override
    public String stageDescription() {
        return stageGoal();
    }

    @Override
    public HistoryPolicy historyPolicy() {
        return super.historyPolicy().withMaxMessages(6);
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("extract_slots · system", extractPrompt());
        prompts.put("extract_slots · user", USER.formatted("{已收集信息}", "{用户要求修改的字段}", "{最近6条对话}", "{用户本轮消息}"));
        prompts.put("ask_missing · system", FollowUp.previewSystem(support.assistant(), taskContext(), stageGoal()));
        prompts.put("ask_missing · user", FollowUp.previewUser());
        prompts.put("ask_missing · 说明", "本轮记下的信息、校验提示和需要用户选择的候选由代码先输出；问哪几项（最多两项）由代码决定，"
                + "模型只负责措辞。当前方式：" + askMode() + "（CODE 或模型超时、出错时使用代码文案）");
        return prompts;
    }

    private static final String USER = """
            已收集信息：%s
            用户要求修改、等待新值的字段：%s
            最近对话：
            %s
            【用户本轮消息】：%s
            """;

    private List<SlotSpec> allSlots() {
        return members.stream().flatMap(m -> m.slots().stream()).toList();
    }

    private String extractPrompt() {
        return SlotFillingNode.extractionPrompt(taskContext(), stageGoal(), allSlots(), EXTRA_RULES);
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());

        // 1. 一次抽取
        List<String> reasons = new ArrayList<>();
        Map<String, Object> extracted;
        try {
            extracted = extract(state, scope, task);
        } catch (LlmJsonException e) {
            log.warn("槽位抽取失败，本轮按未抽取到处理 node={}", id(), e);
            extracted = Map.of();
            reasons.add(unreadableText());
        }

        // 2. 各成员校验合并
        List<String> saved = new ArrayList<>();
        Map<SlotFillingNode, SlotFillingNode.Collected> results = new LinkedHashMap<>();
        for (SlotFillingNode member : members) {
            if (!member.mentions(extracted)) {
                continue;
            }
            SlotFillingNode.Collected result = member.collect(task, extracted, scope);
            results.put(member, result);
            if (result.changed()) {
                String description = member.describe(task.getSlots());
                if (!description.equals("（暂无）")) {
                    saved.add(description);
                }
            }
            result.errors().messages().stream().filter(m -> !reasons.contains(m)).forEach(reasons::add);
        }
        for (SlotFillingNode member : members) {
            if (!results.containsKey(member) || results.get(member).errors().isEmpty()) {
                member.revalidate(task.getSlots()).messages().stream()
                        .filter(m -> !reasons.contains(m)).forEach(reasons::add);
            }
        }

        // 3. 推进或追问
        Map<String, Object> updates = updates();
        boolean hasErrors = results.values().stream().anyMatch(r -> !r.errors().isEmpty());
        if (complete(task) && !hasErrors && reasons.isEmpty()) {
            task.setAwaiting(null);
            edit.advanceTo(nextNode());
            goTo(updates, nextNode());
            return edit.writeTo(updates);
        }

        List<SlotAsk.Entry> entries = new ArrayList<>();
        for (SlotFillingNode member : members) {
            SlotFillingNode.Collected result = results.get(member);
            SlotErrors errors = result == null ? new SlotErrors() : result.errors();
            entries.add(new SlotAsk.Entry(member, member.pendingLabels(task), member.corrections(task, errors),
                    result != null && result.changed()));
        }
        SlotAsk.Prepared plan = SlotAsk.prepare(entries, task);

        List<String> facts = new ArrayList<>();
        if (!saved.isEmpty()) {
            facts.add("当前已记录：" + String.join("；", saved) + "。");
        }
        if (!reasons.isEmpty()) {
            facts.add(String.join("；", reasons) + "。");
        }
        facts.addAll(plan.choices());
        boolean repeated = plan.codeQuestion().equals(task.getAwaiting());
        task.setAwaiting(plan.codeQuestion());

        StringBuilder text = new StringBuilder();
        if (!facts.isEmpty()) {
            appendText(scope, text, String.join("\n", facts));
        }
        FollowUp.Plan followUp = new FollowUp.Plan(stageGoal(), taskContext(), plan.askItems(), plan.later(),
                reasons, plan.correcting(), repeated, plan.choices().isEmpty() ? List.of() : List.of(SlotAsk.CHOICES_NOTE));
        updates.put(FlowState.REPLIES, askFollowUp(state, scope, followUp, text, plan.codeQuestion()));
        // 停在组节点：下一轮回答任意一项都回到这里
        edit.advanceTo(id());
        return edit.writeTo(updates);
    }

    /** 本轮输入整体没能识别时告诉用户的话（不带句末标点） */
    protected String unreadableText() {
        return "这句话我没能识别出需要的信息";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extract(FlowState state, NodeScope scope, TaskFrame task) {
        String collected = describe(task.getSlots());
        String user = USER.formatted(collected.isEmpty() ? "（暂无）" : collected, task.getEditingSlots(),
                historyAsText(state, historyPolicy().maxMessages()), state.userInput());
        List<ChatMessage> messages = List.of(system(extractPrompt()), UserMessage.from(user));
        Map<String, Object> out = chatJson(scope, "extract_slots", support.models().chat(), messages, Map.class);
        return out == null ? Map.of() : out;
    }
}

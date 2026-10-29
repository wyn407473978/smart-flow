package com.example.smartflow.node.slot;

import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.HistoryPolicy;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.NodeType;
import com.example.smartflow.node.TaskStackEdit;
import com.example.smartflow.node.slot.Slots.MergeResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用的多轮信息收集节点（任务的一个阶段）。子类只声明要收集什么，流程由基类完成：
 * <ol>
 *   <li>模型抽取：只抽取用户本轮明确说到的信息（抽取提示词由 {@link #slots()} 生成）</li>
 *   <li>代码校验并合并：类型、范围、可选值、跨字段规则都由代码判断，模型不参与</li>
 *   <li>收齐了就推进到 {@link #nextNode()} 并在本轮内直接执行它；没收齐就流式追问还缺的信息</li>
 * </ol>
 *
 * 最小实现：
 * <pre>
 * &#64;Component
 * public class CollectContactNode extends SlotFillingNode {
 *     public CollectContactNode(NodeSupport support) { super(support); }
 *     &#64;Override public String id() { return "collect_contact"; }
 *     &#64;Override protected String stageGoal() { return "收集联系人信息"; }
 *     &#64;Override protected String nextNode() { return "confirm_order"; }
 *     &#64;Override protected List&lt;SlotSpec&gt; slots() {
 *         return List.of(SlotSpec.string("contactName", "联系人").build(),
 *                        SlotSpec.string("phone", "手机号").maxLength(20).build());
 *     }
 * }
 * </pre>
 *
 * 需要定制时覆盖：{@link #normalize}（抽取结果换算，例如"年龄不限"→ 0～120）、
 * {@link #validateTogether}（跨字段校验）、{@link #describe}（确认文案里的描述）、{@link #completedText}。
 * <p>
 * 从确认节点"修改信息"回来时，所有槽位都已收齐：用户这句话改到了本阶段的信息就会说一句确认，
 * 没改到就不说话直接往后走，最后回到确认节点重新确认。
 */
public abstract class SlotFillingNode extends AbstractNode {

    private static final String EXTRACT_PROMPT = """
            你是信息抽取模块。%s
            当前步骤：%s。你需要从【用户本轮消息】中抽取下面这些字段。

            字段说明：
            %s

            规则：
            1. 只抽取用户本轮消息里明确提到的信息，没提到的字段一律输出 null，不要根据已收集的信息重复输出。
            2. 用户修改之前的信息（例如"改成女性"）时，输出修改后的值。
            3. 不要猜测。

            只输出 JSON，格式：
            %s
            """;

    private static final String ASK_PROMPT = """
            你是"%s"，%s。
            %s当前步骤：%s。

            已收集：%s
            还缺少：%s
            %s
            请用一到两句话回复用户：如果本轮刚收集到新信息，先简短确认；然后自然地追问还缺少的信息。
            语气亲切简洁，不要编造用户没说过的信息，不要提到"槽位""字段"这类技术词。
            """;

    protected SlotFillingNode(NodeSupport support) {
        super(support);
    }

    // =====================================================================
    // 子类声明
    // =====================================================================

    /** 本阶段要收集的槽位，顺序就是追问和描述的顺序 */
    protected abstract List<SlotSpec> slots();

    /** 本阶段在做什么，例如"收集活动的目标人群（年龄范围、性别、目标区域或社区）"。也会告诉路由模型 */
    protected abstract String stageGoal();

    /**
     * 收齐后任务推进到哪个节点（任务的下一阶段），本轮内会直接执行它。
     * 返回 null 表示收齐即任务完成。
     */
    protected abstract String nextNode();

    /** 用户正在办的事的背景，写进抽取和追问提示词，例如"用户正在创建一个民生活动。"，可以为空 */
    protected String taskContext() {
        return "";
    }

    // =====================================================================
    // 可选的定制点
    // =====================================================================

    /**
     * 合并前对抽取结果做换算（原地修改）。例如把"年龄不限"换算成 0～120，
     * 或者用户只说了下限时把上限设成 {@link Slots#CLEAR}。默认不处理。
     */
    protected void normalize(Map<String, Object> extracted) {
    }

    /**
     * 跨字段校验：candidate 是本轮合并后的候选结果，有问题时 {@code errors.reject(原因, 槽位...)}，
     * 被拒绝的槽位保持本轮之前的值。errors 里已经有单个字段校验的结果，可以用 isRejected 判断。默认不校验。
     */
    protected void validateTogether(Map<String, Object> candidate, SlotErrors errors) {
    }

    /** 已收集信息的描述，用于提示词、确认文案。默认"标签：值"用顿号连接 */
    public String describe(Map<String, Object> slots) {
        return Slots.describe(slots(), slots);
    }

    /** 还缺的必填项 */
    public List<String> missing(Map<String, Object> slots) {
        return Slots.missing(slots(), slots);
    }

    /** 收齐时的确认文案（只在本轮有信息变化时说） */
    protected String completedText(Map<String, Object> slots) {
        return "好的，已记录：" + describe(slots) + "。";
    }

    /**
     * 换算 + 校验 + 合并，原地修改 slots。纯代码，不调用模型，便于单测。
     *
     * @param extracted 模型抽取的原始结果
     */
    public MergeResult applyExtraction(Map<String, Object> slots, Map<String, Object> extracted) {
        Map<String, Object> ex = extracted == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extracted);
        normalize(ex);
        return Slots.merge(slots(), slots, ex, this::validateTogether);
    }

    // =====================================================================
    // 基类行为
    // =====================================================================

    @Override
    public NodeType type() {
        return NodeType.SLOT_FILLING;
    }

    @Override
    public String stageDescription() {
        return stageGoal();
    }

    /** 抽取和追问只需要最近几轮；带上长期记忆（例如用户常住的社区），可以帮用户提建议 */
    @Override
    public HistoryPolicy historyPolicy() {
        return super.historyPolicy().withMaxMessages(6);
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("extract_slots · system", extractPrompt());
        prompts.put("ask_missing · system", ASK_PROMPT.formatted(
                support.assistant().name(), support.assistant().persona(), contextLine(), stageGoal(),
                "{已收集信息}（本轮有/没有新收集到的信息）", "{还缺少的信息}", "{校验错误，没有则为空}\n"));
        return prompts;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());

        // 1. 抽取  2. 校验 + 合并
        MergeResult result = applyExtraction(task.getSlots(), extract(state, scope, task));

        // 3. 推进或追问
        List<String> missing = missing(task.getSlots());
        Map<String, Object> updates = updates();
        if (missing.isEmpty() && result.errors().isEmpty()) {
            String next = nextNode();
            if (result.changed() || next == null) {
                updates.put(FlowState.REPLIES, emitText(scope, completedText(task.getSlots())));
            }
            if (next == null) {
                edit.complete();
            } else {
                edit.advanceTo(next);
                goTo(updates, next);
            }
        } else {
            updates.put(FlowState.REPLIES, ask(state, scope, task.getSlots(), missing, result));
        }
        return edit.writeTo(updates);
    }

    // ------------------------------------------------------------------ 抽取

    private String contextLine() {
        String ctx = taskContext() == null ? "" : taskContext().strip();
        return ctx.isEmpty() ? "" : ctx + "\n";
    }

    private String extractPrompt() {
        return EXTRACT_PROMPT.formatted(contextLine().strip(), stageGoal(), Slots.fieldGuide(slots()),
                Slots.jsonTemplate(slots()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extract(FlowState state, NodeScope scope, TaskFrame task) {
        String lastAssistant = state.history().isEmpty() ? "（无）"
                : state.history().get(state.history().size() - 1).content();
        String user = """
                已收集信息：%s
                助手上一句话：%s
                【用户本轮消息】：%s
                """.formatted(describe(task.getSlots()), lastAssistant, state.userInput());
        List<ChatMessage> messages = List.of(system(extractPrompt()), UserMessage.from(user));
        try {
            Map<String, Object> out = chatJson(scope, "extract_slots", support.models().chat(), messages, Map.class);
            return out == null ? Map.of() : out;
        } catch (LlmJsonException e) {
            log.warn("槽位抽取失败，本轮按未抽取到处理 node={}", id(), e);
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ 追问

    private String ask(FlowState state, NodeScope scope, Map<String, Object> slots,
                       List<String> missing, MergeResult result) throws Exception {
        List<String> errors = result.errors().messages();
        String errorLine = errors.isEmpty() ? ""
                : "用户本轮提供的信息有问题，需要请用户重新确认：" + String.join("；", errors) + "\n";
        String newInfoLine = result.changed() ? "（本轮有新收集到的信息）" : "（本轮没有收集到新信息）";
        String sys = ASK_PROMPT.formatted(
                support.assistant().name(), support.assistant().persona(), contextLine(), stageGoal(),
                describe(slots) + " " + newInfoLine,
                missing.isEmpty() ? "（无）" : String.join("、", Slots.missingWithHints(slots(), missing)),
                errorLine);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system(sys + memoryBlock(state)));
        messages.addAll(historyMessages(state));
        messages.add(UserMessage.from(state.userInput()));
        return streamReply(scope, "ask_missing", messages);
    }
}

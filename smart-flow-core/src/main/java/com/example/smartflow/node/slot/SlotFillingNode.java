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
 *   <li>收齐了就推进到 {@link #nextNode()} 并在本轮内直接执行它；没收齐就追问：已记录的信息和校验提示由代码输出，
 *       这轮问哪几项由代码决定，措辞默认交给模型（{@link AskMode}），模型超时或出错时用代码文案</li>
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
 * {@link #validateTogether}（跨字段校验）、{@link #describe}（确认文案里的描述）、{@link #completedText}、{@link #askMode}。
 * <p>
 * 自动推进时只保存信息，不逐阶段输出确认；后续追问统一说明本轮保存的信息，
 * 或由确认节点展示完整汇总。没有下一阶段时仍输出完成文案。
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

    /** 供路由目录沿任务阶段查找槽位归属，模型不能自行指定任意节点。 */
    public final String nextStage() {
        return nextNode();
    }

    /** 合并阶段后接替的旧节点 ID，用于让旧任务的修改请求仍能找到字段归属。 */
    public List<String> previousStageIds() {
        return List.of();
    }

    public final List<SlotSpec> editableSlots() {
        return slots().stream().filter(s -> !s.extractOnly()).toList();
    }

    /** 用户正在办的事的背景，写进抽取提示词，例如"用户正在创建一个民生活动。"，可以为空 */
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

    /** 没有下一阶段、收齐即完成任务时的文案；自动推进时不调用。 */
    protected String completedText(Map<String, Object> slots) {
        return "好的，已记录：" + describe(slots) + "。";
    }

    /**
     * 换算 + 校验 + 合并，原地修改 slots。纯代码，不调用模型，便于单测。
     *
     * @param extracted 模型抽取的原始结果
     */
    public MergeResult applyExtraction(Map<String, Object> slots, Map<String, Object> extracted) {
        return Slots.merge(slots(), slots, normalizedExtraction(extracted), this::validateTogether);
    }

    private Map<String, Object> normalizedExtraction(Map<String, Object> extracted) {
        Map<String, Object> ex = extracted == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extracted);
        normalize(ex);
        return ex;
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

    /** 抽取只需要最近几轮，用来理解用户对上一句追问的简短回答 */
    @Override
    public HistoryPolicy historyPolicy() {
        return super.historyPolicy().withMaxMessages(6);
    }

    @Override
    public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("extract_slots · system", extractPrompt());
        prompts.put("ask_missing · system", FollowUp.previewSystem(support.assistant(), contextLine(), stageGoal()));
        prompts.put("ask_missing · user", FollowUp.previewUser());
        prompts.put("ask_missing · 说明", "已记录信息和校验提示由代码先输出；追问计划由代码决定，模型只负责措辞。"
                + "当前方式：" + askMode() + "（CODE 或模型超时、出错时使用代码文案，例如\"请补充：年龄范围、性别。\"）");
        return prompts;
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());

        // 1. 抽取  2. 校验 + 合并
        Map<String, Object> extracted = normalizedExtraction(extract(state, scope, task));
        MergeResult result = Slots.merge(slots(), task.getSlots(), extracted, this::validateTogether);
        // "想改区域"不能因为旧值仍在就直接跳过；只有本轮有效抽取并通过校验才结束该项修改。
        for (SlotSpec spec : editableSlots()) {
            Object raw = extracted.get(spec.name());
            if (raw != null && !result.errors().isRejected(spec.name())
                    && (raw == Slots.CLEAR || Slots.convert(spec, raw).value() != null)) {
                task.getEditingSlots().remove(spec.name());
            }
        }

        // 3. 推进或追问
        List<String> missing = new ArrayList<>(missing(task.getSlots()));
        for (SlotSpec spec : editableSlots()) {
            if (task.getEditingSlots().contains(spec.name()) && !missing.contains(spec.askLabel())) {
                missing.add(spec.askLabel());
            }
        }
        Map<String, Object> updates = updates();
        if (missing.isEmpty() && result.errors().isEmpty()) {
            String next = nextNode();
            if (next == null) {
                updates.put(FlowState.REPLIES, emitText(scope, completedText(task.getSlots())));
                edit.complete();
            } else {
                if (result.changed()) {
                    List<FlowState.CollectedStageInfo> collected = new ArrayList<>(state.collectedStageInfo());
                    collected.removeIf(info -> info.taskId().equals(task.getTaskId()) && info.nodeId().equals(id()));
                    collected.add(new FlowState.CollectedStageInfo(task.getTaskId(), id(), describe(task.getSlots())));
                    updates.put(FlowState.COLLECTED_STAGE_INFO, collected);
                }
                edit.advanceTo(next);
                goTo(updates, next);
            }
        } else {
            updates.put(FlowState.REPLIES, ask(state, scope, task, missing, result));
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
        String lastAssistant = lastAssistantMessage(state);
        String user = """
                已收集信息：%s
                用户要求修改、等待新值的字段：%s
                助手上一句话：%s
                【用户本轮消息】：%s
                """.formatted(describe(task.getSlots()), task.getEditingSlots(), lastAssistant, state.userInput());
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

    /**
     * 追问分两部分，"说什么"都由代码决定：
     * <ol>
     *   <li>事实：本轮保存了什么、哪里填得不对。由代码拼好立刻输出，模型拿不到也改不了</li>
     *   <li>追问：这轮问哪几项（最多两项）由代码选定。{@link AskMode#MODEL} 下由模型措辞并流式输出，
     *       超时、出错或没有输出时换成代码文案；{@link AskMode#CODE} 下直接用代码文案</li>
     * </ol>
     * 任务的 awaiting 始终记代码文案：路由靠它判断用户在回答什么，也用它发现同一个问题被连续问了两次。
     */
    private String ask(FlowState state, NodeScope scope, TaskFrame task,
                       List<String> missing, MergeResult result) {
        // 1. 事实
        List<String> facts = new ArrayList<>();
        List<String> saved = new ArrayList<>(state.collectedStageInfo().stream()
                .filter(info -> info.taskId().equals(task.getTaskId()) && !info.nodeId().equals(id()))
                .map(FlowState.CollectedStageInfo::description).toList());
        if (result.changed()) saved.add(describe(task.getSlots()));
        if (!saved.isEmpty()) facts.add("当前已记录：" + String.join("；", saved) + "。");
        if (!result.errors().messages().isEmpty()) {
            facts.add(String.join("；", result.errors().messages()) + "。");
        }

        // 2. 追问计划：优先纠正无效值和完成用户要求的修改，再补齐缺失项。所有问题均来自声明的槽位。
        List<String> corrections = editableSlots().stream()
                .filter(s -> task.getEditingSlots().contains(s.name()) || result.errors().isRejected(s.name()))
                .map(SlotSpec::askLabel).distinct().toList();
        boolean correcting = !corrections.isEmpty();
        List<String> asking = (correcting ? corrections : missing).stream().distinct().limit(2).toList();
        List<String> later = missing.stream().filter(label -> !asking.contains(label)).distinct().toList();
        // 跨字段校验也可能只给错误说明而未指定字段，此时让用户根据上面的错误重新提供内容。
        String codeQuestion = asking.isEmpty() ? "请根据上述提示重新提供信息。"
                : (correcting ? "请重新提供：" : "请补充：") + String.join("、", Slots.missingWithHints(slots(), asking)) + "。";
        boolean repeated = codeQuestion.equals(task.getAwaiting());
        task.setAwaiting(codeQuestion);

        // 3. 输出：事实先出，再接追问
        StringBuilder text = new StringBuilder();
        if (!facts.isEmpty()) {
            appendText(scope, text, String.join("\n", facts));
        }
        FollowUp.Plan plan = new FollowUp.Plan(stageGoal(), contextLine(),
                Slots.missingWithHints(slots(), asking), later, result.errors().messages(), correcting, repeated, List.of());
        return askFollowUp(state, scope, plan, text, codeQuestion);
    }
}

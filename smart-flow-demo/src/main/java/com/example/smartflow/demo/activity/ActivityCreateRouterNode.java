package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.TaskStackEdit;
import com.example.smartflow.node.route.IntentRouterNode;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteSpec;
import com.example.smartflow.node.slot.FollowUp;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 短信活动的唯一入口。按本轮提及的参数分发多个子节点，四项均有效后才进入提交。 */
@Component
public class ActivityCreateRouterNode extends IntentRouterNode {
    static final String INPUT = "activityInput";
    static final String PENDING = "activityPending";
    static final String ERRORS = "activityErrors";
    /** 本轮输入整体没能识别时的错误键（不属于任何一项参数） */
    static final String INPUT_ERROR = "input";
    private static final String STAGE = "收集创建短信活动需要的发送区域、发送人群、发送时间和短信模版，顺序不限";
    private final Clock clock;
    private final Map<String, ActivityCollectionNode> collectors;

    @Autowired
    public ActivityCreateRouterNode(NodeSupport support, RouteCatalog catalog, List<ActivityCollectionNode> nodes) {
        this(support, catalog, nodes, Clock.system(ActivityData.ZONE));
    }
    public ActivityCreateRouterNode(NodeSupport support, RouteCatalog catalog, List<ActivityCollectionNode> nodes, Clock clock) {
        super(support, catalog);
        this.clock = clock;
        this.collectors = nodes.stream().collect(Collectors.toMap(ActivityCollectionNode::section, n -> n));
        if (!collectors.keySet().equals(new HashSet<>(ActivityData.SECTIONS)))
            throw new IllegalArgumentException("活动创建必须配置区域、人群、时间、模版四个收集节点");
    }

    @Override public String id() { return MinshengNames.ACTIVITY_CREATE; }
    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.taskEntry(FlowNames.ROUTER, MinshengNames.TASK_ACTIVITY_CREATE, "短信活动创建",
                "用户要创建、发送、安排短信活动，或继续提供活动区域、人群、发送时间、短信内容和模版选择",
                List.of("创建一个短信活动", "给西湖区20岁以上男性发短信", "明天9点发送健康讲座通知"), 10));
    }
    @Override public String stageDescription() {
        return "收集短信活动区域、人群、发送时间、短信模版（任意顺序）；模版须从三个推荐中明确选一个；信息齐全才确认提交";
    }

    @Override public String systemPrompt() {
        String children = catalog.options(id()).stream().map(o -> "- " + o.spec().intent() + "（" + o.spec().name()
                + "）：" + o.spec().description()).collect(Collectors.joining("\n"));
        return """
                你是短信活动创建路由，只理解本轮输入并抽取参数，分发和完成条件由代码决定。
                子节点：
                %s
                同一句提到的全部参数都必须抽取，没有先后顺序。只抽取用户明确提供的信息，不能补造地址、年龄或短信事实。
                region：发送区域，保留用户原话，不自动纠正街道名或虚构行政区划；没提到为null。
                allAudience：明确全部人群/不限为true，否则null；仅“男性”或“20岁以上”本身就是有效人群条件。
                gender：MALE/FEMALE/ALL或null；年龄边界为0到120的整数。
                ageMin/ageMax：本轮年龄范围，只有一端时另一端null；“大于20岁”用21，“20岁以上”用20。
                ageOpenEnded：只给了一端年龄时，用户说的是单边范围（“20岁以上”“60岁以下”“大于20岁”）为true；
                只是调整一端（“上限改成70”“下限改为30岁”）为false；没提年龄为null。
                sendTime：结合当前北京时间和最近对话，把明确日期和具体时刻转换为yyyy-MM-dd HH:mm:ss。
                用户上轮只说“明天”、本轮补“上午9点”时须合并理解为明天9点；只沿用用户已明确的日期，不能猜测。
                “明天9点”可转换；只有“明天”“下周六上午”不能擅定时刻，此时填空字符串要求追问。
                本轮没提时间填null。错误或过去的时间也交给代码校验，不得悄悄改成未来。
                templateBrief：用户提供的短信原文或使用场景（如健康讲座、停水通知）；没提到填null。
                templateChoice：用户从已展示的三个短信候选中明确选择的1/2/3；“第二个”“用第一条”可识别。
                只有在等待模版选择且已有候选时，“好的/就这个”才可结合上文识别；无法确定具体编号则null。
                更换场景/短信原文时不得沿用旧选择；不能替用户选择推荐。
                editSections：用户要求修改的分组编码region/audience/time/template（可以多个）；仅说要改但没给新值也要填。
                只输出JSON：
                {"region":null,"allAudience":null,"gender":null,"ageMin":null,"ageMax":null,"ageOpenEnded":null,
                 "sendTime":null,"templateBrief":null,"templateChoice":null,"editSections":[]}
                """.formatted(children);
    }

    @Override public Map<String, String> promptPreview() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("activity_route · system", systemPrompt());
        prompts.put("activity_route · user",
                "当前北京时间：{yyyy-MM-dd HH:mm:ss}\n已收集信息：{活动参数及三个候选模版}\n正在等用户回答：{问题}\n最近对话：{最近6条}\n用户消息：{本轮输入}");
        prompts.put("ask_missing · system", FollowUp.previewSystem(support.assistant(), null, STAGE));
        prompts.put("ask_missing · user", FollowUp.previewUser());
        prompts.put("ask_missing · 说明", "本轮记下的参数、校验提示和推荐的三个模版原文由代码先输出；问哪几项（最多两项）由代码决定，"
                + "模型只负责措辞。当前方式：" + askMode() + "（CODE 或模型超时、出错时使用各收集节点的固定问题）");
        return prompts;
    }

    @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) {
        TaskStackEdit edit = editTasks(state);
        TaskFrame task = edit.requireActiveTask(id());
        Map<String, Object> updates = updates();
        List<String> pending;
        if (state.value(INPUT).isEmpty()) {
            ActivityInput input;
            try {
                String user = "当前北京时间：" + LocalDateTime.now(clock.withZone(ActivityData.ZONE)).format(ActivityData.TIME_FORMAT)
                        + "\n已收集信息：" + task.getSlots() + "\n正在等用户回答：" + task.getAwaiting()
                        + "\n最近对话：" + historyAsText(state, 6)
                        + "\n用户消息：" + state.userInput();
                input = chatJson(scope, "activity_route", support.models().chat(),
                        List.of(system(systemPrompt()), UserMessage.from(user)), ActivityInput.class);
                if (input == null) input = ActivityInput.empty();
            } catch (LlmJsonException e) {
                input = ActivityInput.empty();
                updates.put(ERRORS, new LinkedHashMap<>(Map.of(INPUT_ERROR, "这句话我没能识别出活动信息")));
            }
            updates.put(INPUT, input);
            if (input.editSections() != null) {
                input.editSections().stream().filter(ActivityData.SECTIONS::contains).forEach(section -> {
                    if (!task.getEditingSlots().contains(section)) task.getEditingSlots().add(section);
                });
            }
            pending = new ArrayList<>();
            for (String section : ActivityData.SECTIONS) if (input.touches(section)) pending.add(section);
        } else {
            pending = new ArrayList<>(state.<List<String>>value(PENDING).orElse(List.of()));
        }

        if (!pending.isEmpty()) {
            String section = pending.remove(0);
            updates.put(PENDING, pending);
            edit.advanceTo(collectors.get(section).id());
            goTo(updates, collectors.get(section).id());
        } else {
            List<String> missing = ActivityData.SECTIONS.stream().filter(s -> !ActivityData.filled(s, task, clock)).toList();
            Map<String, String> errors = new LinkedHashMap<>(state.<Map<String, String>>value(ERRORS).orElse(Map.of()));
            if (updates.get(ERRORS) instanceof Map<?, ?> fresh) fresh.forEach((k, v) -> errors.put(k.toString(), v.toString()));
            // 已保存的发送时间过期了（例如在确认前拖过了发送时间）：明确告诉用户，而不是只当作没填
            if (missing.contains("time") && !errors.containsKey("time")
                    && ActivityData.parse(task.getSlots().get("sendTime")) != null
                    && !ActivityData.future(task.getSlots().get("sendTime"), clock)) {
                errors.put("time", "原定的发送时间 " + ActivityData.display(task.getSlots().get("sendTime")) + " 已经过去了");
            }
            if (missing.isEmpty() && errors.isEmpty()) {
                task.getSlots().remove("waitingSection");
                edit.advanceTo(MinshengNames.ACTIVITY_SUBMIT);
                goTo(updates, MinshengNames.ACTIVITY_SUBMIT);
            } else {
                updates.put(ERRORS, errors);
                updates.put(FlowState.REPLIES, ask(state, scope, task, missing, errors));
                // 追问结束后回到活动路由，下一轮可以回答任意一项
                edit.advanceTo(id());
            }
        }
        return edit.writeTo(updates);
    }

    /**
     * 追问。和 {@link com.example.smartflow.node.slot.SlotFillingNode} 同一个原则：说什么由代码决定，模型只负责措辞。
     * <ol>
     *   <li>代码先输出本轮记下的参数（没有新内容时简短回顾已有的）和校验提示</li>
     *   <li>本轮刚推荐的三个短信模版，或者这轮要用户选模版时，由代码原文列出，不交给模型改写</li>
     *   <li>代码选出本轮要问的一两项：校验失败和要求修改的优先，其次刚推荐了模版就请用户选，
     *       再其次上一轮在等的那项，最后按区域、人群、时间、模版的顺序补缺</li>
     *   <li>追问模型按计划写一两句话；ask-mode 为 code 或模型失败时，用各收集节点的固定问题</li>
     * </ol>
     */
    private String ask(FlowState state, NodeScope scope, TaskFrame task, List<String> missing, Map<String, String> errors) {
        ActivityInput input = state.<ActivityInput>value(INPUT).orElse(ActivityInput.empty());
        List<String> touched = ActivityData.SECTIONS.stream().filter(input::touches).toList();
        List<String> candidates = ActivityData.candidates(task.getSlots());
        boolean freshCandidates = input.templateBrief() != null && !errors.containsKey("template") && candidates.size() == 3;

        // 1. 事实：本轮记下的参数；什么都没记下也没有错误时，简短回顾已有参数
        List<String> facts = new ArrayList<>();
        List<String> saved = touched.stream().filter(s -> !errors.containsKey(s))
                .map(s -> collectors.get(s).describeSaved(task)).filter(Objects::nonNull).toList();
        if (!saved.isEmpty()) {
            facts.add("已记录：" + String.join("；", saved) + "。");
        } else if (errors.isEmpty() && !freshCandidates) {
            List<String> kept = ActivityData.SECTIONS.stream().filter(s -> ActivityData.filled(s, task, clock))
                    .map(s -> collectors.get(s).describeSaved(task)).filter(Objects::nonNull).toList();
            if (!kept.isEmpty()) facts.add("目前已记录：" + String.join("；", kept) + "。");
        }
        if (!errors.isEmpty()) facts.add(ActivityData.sentence(errors.values()));

        // 2. 追问计划
        String waiting = String.valueOf(task.getSlots().get("waitingSection"));
        LinkedHashSet<String> order = new LinkedHashSet<>();
        ActivityData.SECTIONS.stream().filter(errors::containsKey).forEach(order::add);
        ActivityData.SECTIONS.stream().filter(s -> task.getEditingSlots().contains(s) && missing.contains(s)).forEach(order::add);
        if (freshCandidates && missing.contains("template")) order.add("template");
        if (missing.contains(waiting)) order.add(waiting);
        order.addAll(missing);
        List<String> asking = order.stream().limit(2).toList();
        List<String> later = missing.stream().filter(s -> !asking.contains(s)).toList();
        boolean correcting = asking.stream().anyMatch(s -> errors.containsKey(s) || task.getEditingSlots().contains(s));

        // 3. 模版原文：刚推荐的，或者这轮要用户选的
        boolean showCandidates = candidates.size() == 3 && missing.contains("template")
                && (freshCandidates || asking.contains("template"));
        if (showCandidates) {
            String brief = String.valueOf(task.getSlots().getOrDefault("templateBrief", ""));
            facts.add((freshCandidates ? "根据“" + brief + "”推荐了三个短信模版：" : "可选的短信模版：")
                    + ActivityTemplateNode.listCandidates(candidates));
        }

        String codeQuestion = asking.isEmpty() ? "可以换个说法再告诉我一次吗？"
                : asking.stream().map(s -> collectors.get(s).question(task)).collect(Collectors.joining());
        boolean repeated = codeQuestion.equals(task.getAwaiting());
        task.setAwaiting(codeQuestion);
        if (!asking.isEmpty()) task.getSlots().put("waitingSection", asking.get(0));

        StringBuilder text = new StringBuilder();
        if (!facts.isEmpty()) appendText(scope, text, String.join("\n", facts));
        List<String> notes = showCandidates
                ? List.of("三个短信模版已由系统原样列出，不要复述、改写或评价模版内容，也不要替用户选择，请用户回复编号。")
                : List.of();
        FollowUp.Plan plan = new FollowUp.Plan(STAGE, null,
                asking.stream().map(s -> collectors.get(s).askItem(task)).toList(),
                later.stream().map(s -> collectors.get(s).label()).toList(),
                asking.stream().map(errors::get).filter(Objects::nonNull).toList(),
                correcting, repeated, notes);
        return askFollowUp(state, scope, plan, text, codeQuestion);
    }
}

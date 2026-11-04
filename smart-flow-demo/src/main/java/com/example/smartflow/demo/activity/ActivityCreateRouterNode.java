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
    static final String ASK = "activityAsk";
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
                {"region":null,"allAudience":null,"gender":null,"ageMin":null,"ageMax":null,
                 "sendTime":null,"templateBrief":null,"templateChoice":null,"editSections":[]}
                """.formatted(children);
    }

    @Override public Map<String, String> promptPreview() {
        return Map.of("activity_route · system", systemPrompt(), "activity_route · user",
                "当前北京时间：{yyyy-MM-dd HH:mm:ss}\n已收集信息：{活动参数及三个候选模版}\n正在等用户回答：{问题}\n最近对话：{最近6条}\n用户消息：{本轮输入}");
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
                updates.put(ERRORS, new LinkedHashMap<>(Map.of("region", "本轮信息未能识别，请换个说法。")));
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
            updates.put(ASK, false);
            edit.advanceTo(collectors.get(section).id());
            goTo(updates, collectors.get(section).id());
        } else {
            List<String> missing = ActivityData.SECTIONS.stream().filter(s -> !ActivityData.filled(s, task, clock)).toList();
            Map<String, String> errors = new LinkedHashMap<>(state.<Map<String, String>>value(ERRORS).orElse(Map.of()));
            if (updates.get(ERRORS) instanceof Map<?, ?> fresh) fresh.forEach((k, v) -> errors.put(k.toString(), v.toString()));
            if (missing.isEmpty() && errors.isEmpty()) {
                task.getSlots().remove("waitingSection");
                edit.advanceTo(MinshengNames.ACTIVITY_SUBMIT);
                goTo(updates, MinshengNames.ACTIVITY_SUBMIT);
            } else {
                String waiting = String.valueOf(task.getSlots().get("waitingSection"));
                String target = ActivityData.SECTIONS.stream().filter(errors::containsKey).findFirst()
                        .orElseGet(() -> missing.contains(waiting) ? waiting : missing.get(0));
                updates.put(ERRORS, errors);
                updates.put(ASK, true);
                edit.advanceTo(collectors.get(target).id());
                goTo(updates, collectors.get(target).id());
            }
        }
        return edit.writeTo(updates);
    }
}

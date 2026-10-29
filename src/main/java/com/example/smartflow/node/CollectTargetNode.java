package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.route.RouteSpec;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C1：活动创建 - 收集目标人群（年龄、性别、区域）。
 * <p>
 * 典型的槽位填充节点，分三步：
 * <ol>
 *   <li>模型抽取：只抽取用户本轮明确说到的信息（JSON）</li>
 *   <li>代码校验并合并：取值范围、枚举值都由代码判断，模型不参与</li>
 *   <li>收齐了就把任务推进到 C2，并在同一轮里直接进入 C2；没收齐就流式追问</li>
 * </ol>
 */
@Component
public class CollectTargetNode extends AbstractNode {

    public static final String SLOT_AGE_MIN = "ageMin";
    public static final String SLOT_AGE_MAX = "ageMax";
    public static final String SLOT_GENDER = "gender";
    public static final String SLOT_REGION = "region";

    private static final String EXTRACT_PROMPT = """
            你是信息抽取模块。用户正在创建一个民生活动，你需要从【用户本轮消息】中抽取活动的目标人群信息。

            字段说明：
            - ageMin / ageMax：目标人群年龄下限 / 上限，整数。"60岁以上" → ageMin=60, ageMax=null；"18到35岁" → ageMin=18, ageMax=35。
              常见说法参考：老年人=60岁以上；青年=18到35岁；儿童=0到12岁。
            - ageUnlimited：用户明确说年龄不限时为 true，否则为 null
            - gender：MALE（男性）/ FEMALE（女性）/ ALL（不限性别）
            - region：目标区域或社区名称，保留用户原话

            规则：
            1. 只抽取用户本轮消息里明确提到的信息，没提到的字段一律输出 null，不要根据已收集的信息重复输出。
            2. 用户修改之前的信息（例如"改成女性"）时，输出修改后的值。
            3. 不要猜测。

            只输出 JSON，格式：
            {"ageMin": null, "ageMax": null, "ageUnlimited": null, "gender": null, "region": null}
            """;

    private static final String ASK_PROMPT = """
            你是"智慧民生"助手，正在帮用户创建活动，当前阶段是收集活动的目标人群信息。

            已收集：%s
            还缺少：%s
            %s
            请用一到两句话回复用户：如果本轮刚收集到新信息，先简短确认；然后自然地追问还缺少的信息。
            语气亲切简洁，不要编造用户没说过的信息，不要提到"槽位""字段"这类技术词。
            """;

    /** 模型抽取结果 */
    public record Extraction(Integer ageMin, Integer ageMax, Boolean ageUnlimited, String gender, String region) {
    }

    public CollectTargetNode(NodeSupport support) {
        super(support);
    }

    @Override
    public String id() {
        return FlowNames.COLLECT_TARGET;
    }

    @Override
    public NodeType type() {
        return NodeType.SLOT_FILLING;
    }

    /** 活动创建任务的入口：路由识别到"活动创建"时开启任务，从这个节点开始 */
    @Override
    public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.taskEntry(FlowNames.ROUTER, FlowNames.TASK_ACTIVITY_CREATE, "活动创建",
                "用户想创建、发起、策划一个社区/民生活动",
                List.of("我想办一个活动", "帮我策划一场敬老活动", "社区要搞个亲子活动"),
                10));
    }

    @Override
    public String stageDescription() {
        return "收集活动的目标人群（年龄范围、性别、目标区域或社区）";
    }

    @Override
    protected Map<String, Object> execute(FlowState state, NodeScope scope) throws Exception {
        List<TaskFrame> stack = new ArrayList<>();
        int activeIndex = -1;
        for (TaskFrame f : state.taskStack()) {
            stack.add(f.copy());
            if (f.isOpen()) {
                activeIndex = stack.size() - 1;
            }
        }
        if (activeIndex < 0) {
            throw new IllegalStateException("collect_target 节点没有找到进行中的任务");
        }
        TaskFrame task = stack.get(activeIndex);

        // 1. 抽取
        Extraction ex = extract(state, scope, task);

        // 2. 校验 + 合并
        List<String> errors = new ArrayList<>();
        boolean changed = merge(task.getSlots(), ex, errors);

        // 3. 推进或追问
        List<String> missing = missingSlots(task.getSlots());
        Map<String, Object> updates = updates();
        if (missing.isEmpty() && errors.isEmpty()) {
            // 任务推进到 C2，并在本轮内直接执行 C2
            task.setCurrentNode(FlowNames.CHOOSE_TEMPLATE);
            updates.put(FlowState.REPLIES, emitText(scope, "好的，活动的目标人群确认为：" + describe(task.getSlots()) + "。"));
            goTo(updates, FlowNames.CHOOSE_TEMPLATE);
        } else {
            updates.put(FlowState.REPLIES, ask(state, scope, task.getSlots(), missing, errors, changed));
        }
        updates.put(FlowState.TASK_STACK, stack);
        return updates;
    }

    // ------------------------------------------------------------------ 抽取

    private Extraction extract(FlowState state, NodeScope scope, TaskFrame task) {
        String lastAssistant = state.history().isEmpty() ? "（无）"
                : state.history().get(state.history().size() - 1).content();
        String user = """
                已收集信息：%s
                助手上一句话：%s
                【用户本轮消息】：%s
                """.formatted(describe(task.getSlots()), lastAssistant, state.userInput());
        List<ChatMessage> messages = List.of(system(EXTRACT_PROMPT), UserMessage.from(user));
        try {
            return chatJson(scope, "extract_slots", support.models().chat(), messages, Extraction.class);
        } catch (LlmJsonException e) {
            log.warn("槽位抽取失败，本轮按未抽取到处理", e);
            return new Extraction(null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ 校验与合并（纯代码，便于单测）

    /**
     * 把抽取结果校验后合并进槽位，非法值不写入并记录错误。
     *
     * @return 本轮是否有槽位发生变化
     */
    static boolean merge(Map<String, Object> slots, Extraction ex, List<String> errors) {
        Map<String, Object> before = new LinkedHashMap<>(slots);

        if (Boolean.TRUE.equals(ex.ageUnlimited())) {
            slots.put(SLOT_AGE_MIN, 0);
            slots.put(SLOT_AGE_MAX, 120);
        } else if (ex.ageMin() != null || ex.ageMax() != null) {
            Integer min = ex.ageMin();
            Integer max = ex.ageMax();
            if ((min != null && (min < 0 || min > 120)) || (max != null && (max < 0 || max > 120))) {
                errors.add("年龄需要在 0 到 120 岁之间");
            } else if (min != null && max != null && min > max) {
                errors.add("年龄下限不能大于上限");
            } else {
                slots.put(SLOT_AGE_MIN, min);
                slots.put(SLOT_AGE_MAX, max);
            }
        }

        if (ex.gender() != null) {
            String g = ex.gender().trim().toUpperCase();
            if (List.of("MALE", "FEMALE", "ALL").contains(g)) {
                slots.put(SLOT_GENDER, g);
            } else {
                errors.add("性别只能是男性、女性或不限");
            }
        }

        if (ex.region() != null && !ex.region().isBlank()) {
            String r = ex.region().trim();
            if (r.length() > 50) {
                errors.add("区域名称过长");
            } else {
                slots.put(SLOT_REGION, r);
            }
        }
        return !before.equals(slots);
    }

    static List<String> missingSlots(Map<String, Object> slots) {
        List<String> missing = new ArrayList<>();
        if (slots.get(SLOT_AGE_MIN) == null && slots.get(SLOT_AGE_MAX) == null) {
            missing.add("年龄范围");
        }
        if (slots.get(SLOT_GENDER) == null) {
            missing.add("性别");
        }
        if (slots.get(SLOT_REGION) == null) {
            missing.add("目标区域或社区");
        }
        return missing;
    }

    static String describe(Map<String, Object> slots) {
        List<String> parts = new ArrayList<>();
        Object min = slots.get(SLOT_AGE_MIN);
        Object max = slots.get(SLOT_AGE_MAX);
        if (min != null || max != null) {
            if (Integer.valueOf(0).equals(min) && Integer.valueOf(120).equals(max)) {
                parts.add("年龄不限");
            } else if (min != null && max != null) {
                parts.add(min + "～" + max + " 岁");
            } else if (min != null) {
                parts.add(min + " 岁及以上");
            } else {
                parts.add(max + " 岁及以下");
            }
        }
        Object gender = slots.get(SLOT_GENDER);
        if (gender != null) {
            parts.add(switch (gender.toString()) {
                case "MALE" -> "男性";
                case "FEMALE" -> "女性";
                default -> "性别不限";
            });
        }
        Object region = slots.get(SLOT_REGION);
        if (region != null) {
            parts.add(region.toString());
        }
        return parts.isEmpty() ? "（暂无）" : String.join("、", parts);
    }

    // ------------------------------------------------------------------ 追问

    private String ask(FlowState state, NodeScope scope, Map<String, Object> slots,
                       List<String> missing, List<String> errors, boolean changed) throws Exception {
        String errorLine = errors.isEmpty() ? ""
                : "用户本轮提供的信息有问题，需要请用户重新确认：" + String.join("；", errors) + "\n";
        String newInfoLine = changed ? "（本轮有新收集到的信息）" : "（本轮没有收集到新信息）";
        String sys = ASK_PROMPT.formatted(
                describe(slots) + " " + newInfoLine,
                missing.isEmpty() ? "（无）" : String.join("、", missing),
                errorLine);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system(sys));
        messages.addAll(historyMessages(state, 6));
        messages.add(UserMessage.from(state.userInput()));
        return streamReply(scope, "ask_missing", messages);
    }
}

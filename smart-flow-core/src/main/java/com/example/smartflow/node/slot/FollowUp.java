package com.example.smartflow.node.slot;

import com.example.smartflow.config.FlowProperties;

import java.util.List;

/**
 * 收集信息时的追问："问什么"由代码给出 {@link Plan}，模型只负责把它说成一两句自然的话。
 * <p>
 * 追问模型拿不到已收集的信息，只知道这轮要问哪几项，从源头上避免它确认没保存的内容。
 * 已记录了什么、哪里填得不对，由节点用代码先输出。
 * {@link SlotFillingNode} 直接使用；自定义的收集节点（例如一个路由带多个收集子节点）
 * 通过 {@link com.example.smartflow.node.AbstractNode#askFollowUp} 使用同一套机制。
 */
public final class FollowUp {

    private FollowUp() {
    }

    /**
     * 追问计划。
     *
     * @param stage      当前步骤在做什么，例如"收集短信活动的区域、人群、时间和模版"
     * @param context    业务背景，可为空
     * @param asking     本轮要问的项（建议最多两项），可以带可选值或示例
     * @param later      之后还要了解的项，让用户知道一共要几样东西
     * @param reasons    需要重新提供的原因（系统已经告诉用户），没有则为空
     * @param correcting asking 是否是要用户重新提供的项（修改或校验失败）
     * @param repeated   同一个问题上一轮已经问过
     * @param notes      给模型的额外说明，例如"三个短信模版已由系统原样列出，不要复述"
     */
    public record Plan(String stage, String context, List<String> asking, List<String> later,
                       List<String> reasons, boolean correcting, boolean repeated, List<String> notes) {
        public Plan {
            context = context == null ? "" : context.strip();
            asking = asking == null ? List.of() : List.copyOf(asking);
            later = later == null ? List.of() : List.copyOf(later);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private static final String SYSTEM = """
            你是%s，%s。你现在是追问模块。%s
            当前步骤：%s。
            用户已提供的信息和校验提示，系统已经单独告诉用户了。你只负责接着写一到两句话，向用户追问。

            本轮要问：%s
            之后还要了解：%s
            %s
            规则：
            1. 只问"本轮要问"里的内容；可以顺带提一句之后还要了解哪些，但不要展开问。
            2. 不要复述、确认或总结用户已提供的信息，不要说"好的""已记录""收到"，不要编造选项、信息或承诺。
            3. 用户这句话没给出有效信息时（例如"我再想想""随便""你推荐一个"），先简短回应他，再追问；推荐只能从可选项里选。
            4. 需要用户重新提供时，直接请他重新说，不要重复系统已经说过的原因。
            5. 不要使用 Markdown，不要提到"槽位""字段""节点"这类技术词。
            """;

    private static final String USER = """
            助手上一句话：%s
            用户本轮消息：%s
            """;

    public static String systemPrompt(FlowProperties.Assistant assistant, Plan plan) {
        StringBuilder extra = new StringBuilder();
        if (plan.correcting()) {
            extra.append(plan.reasons().isEmpty()
                    ? "这些信息需要用户重新提供（用户要求修改，或者刚才给的内容不符合要求）。\n"
                    : "这些信息需要用户重新提供，原因（系统已经告诉用户）：" + String.join("；", plan.reasons()) + "。\n");
        }
        if (plan.repeated()) {
            extra.append("同样的问题上一轮已经问过，这次换一种说法，可以把可选项列出来方便用户直接选。\n");
        }
        plan.notes().forEach(note -> extra.append(note).append('\n'));
        return SYSTEM.formatted(assistant.name(), assistant.persona(), plan.context(), plan.stage(),
                String.join("、", plan.asking()),
                plan.later().isEmpty() ? "（无）" : String.join("、", plan.later()),
                extra);
    }

    public static String userPrompt(String lastAssistant, String userInput) {
        return USER.formatted(lastAssistant, userInput);
    }

    /** 「节点 / 提示词」页签展示用 */
    public static String previewSystem(FlowProperties.Assistant assistant, String context, String stage) {
        return SYSTEM.formatted(assistant.name(), assistant.persona(), context == null ? "" : context.strip(), stage,
                "{本轮要问的信息，最多两项，带可选值}", "{之后还要了解的信息，没有则为（无）}",
                "{需要重新提供时的原因；同一问题连问两次时提示换说法；节点的额外说明；都没有则为空}\n");
    }

    public static String previewUser() {
        return USER.formatted("{助手上一句话}", "{用户本轮消息}");
    }
}

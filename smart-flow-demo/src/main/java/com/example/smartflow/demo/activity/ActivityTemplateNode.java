package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.slot.SlotErrors;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import com.example.smartflow.node.slot.Slots;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 短信模版：用户给出短信原文或使用场景后，模型推荐恰好三个不同的完整短信；用户必须明确选定其中一条。
 * 选定的正文是 managed 槽位，只能由代码从候选里写入，模型不能直接填写或替用户选择。
 */
@Component
public class ActivityTemplateNode extends SlotFillingNode {
    private static final String RECOMMEND_PROMPT = """
            你是社区短信文案助手。根据用户提供的短信原文或使用场景，推荐恰好三个不同的完整短信模版。
            保持用户原文事实、意图和已明确的活动信息，给出简洁、亲切、正式三种表达。
            不得编造地点、费用、优惠、联系电话、网址、报名方式或活动时间；缺失细节直接省略，不使用待填写占位符。
            每条1到300字。不要替用户选择。只输出JSON：{"templates":["第一条短信","第二条短信","第三条短信"]}
            """;
    public record Recommendations(List<String> templates) { }
    private static final SlotSpec CHOICE = SlotSpec.integer("templateChoice", "模版编号").build();

    public ActivityTemplateNode(NodeSupport support) { super(support); }

    @Override public String id() { return MinshengNames.ACTIVITY_TEMPLATE; }
    @Override protected String stageGoal() { return "根据短信内容或使用场景推荐三个短信模版，并请用户选定一条"; }
    @Override protected String nextNode() { return MinshengNames.ACTIVITY_CREATE; }

    @Override protected List<SlotSpec> slots() {
        return List.of(
                SlotSpec.string("templateBrief", "短信场景").maxLength(1000).optional()
                        .describe("用户提供的短信原文或使用场景（例如健康讲座、停水通知）；更换场景时输出新的场景；没提到为 null").build(),
                SlotSpec.integer("templateChoice", "模版编号").extractOnly()
                        .describe("用户从已展示的三个短信模版中明确选择的编号 1/2/3，“第二个”“用第一条”可以识别；"
                                + "只有在等用户选模版时“好的”“就这个”才可结合上文；无法确定具体编号为 null；不能替用户选择").build(),
                SlotSpec.string("smsContent", "短信模版").managed().build());
    }

    @Override public Map<String, String> promptPreview() {
        Map<String, String> prompts = new java.util.LinkedHashMap<>(super.promptPreview());
        prompts.put("recommend_templates · system", RECOMMEND_PROMPT);
        return prompts;
    }

    /** 换场景就重新推荐（同一句里的"选1"针对的是旧候选，不生效）；选编号就从当前候选里写入正文 */
    @Override protected void afterMerge(TaskFrame task, Map<String, Object> extracted, SlotErrors errors, NodeScope scope) {
        Map<String, Object> slots = task.getSlots();
        if (extracted.get("templateBrief") != null && !errors.isRejected("templateBrief")
                && slots.get("templateBrief") instanceof String brief) {
            // 更换场景立即撤销旧选择，生成失败也不能误用旧短信提交
            slots.remove("smsContent");
            slots.remove("templateCandidates");
            String problem = recommend(brief, slots, scope);
            if (problem != null) errors.reject(problem, "smsContent");
            return;
        }
        Object raw = extracted.get("templateChoice");
        if (raw == null) return;
        List<String> choices = ActivityData.candidates(slots);
        Object choice = Slots.convert(CHOICE, raw).value();
        if (choices.size() != 3) {
            errors.reject("还没有可选的短信模版，需要先说一下短信内容或使用场景", "smsContent");
        } else if (!(choice instanceof Integer n) || n < 1 || n > 3) {
            errors.reject("只有三个短信模版，请选择1、2或3", "smsContent");
        } else {
            slots.put("smsContent", choices.get(n - 1));
            task.getEditingSlots().remove("smsContent");
        }
    }

    private String recommend(String brief, Map<String, Object> slots, NodeScope scope) {
        try {
            Recommendations result = chatJson(scope, "recommend_templates", support.models().chat(),
                    List.of(system(RECOMMEND_PROMPT), UserMessage.from("用户原文/场景：" + brief)), Recommendations.class);
            List<String> templates = result == null || result.templates() == null ? List.of() : result.templates();
            if (templates.size() != 3 || templates.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 300)
                    || new HashSet<>(templates).size() != 3) {
                return "暂时没能生成三个有效模版，可以换个说法再描述一下短信内容或场景";
            }
            slots.put("templateCandidates", new ArrayList<>(templates));
            return null;
        } catch (LlmJsonException e) {
            return "短信模版推荐暂时失败，可以再描述一次短信场景重试";
        }
    }

    /** 候选原文由代码列出，追问模型不复述也不改写 */
    @Override protected String choicesText(TaskFrame task, boolean fresh) {
        List<String> choices = ActivityData.candidates(task.getSlots());
        if (choices.size() != 3 || task.getSlots().get("smsContent") != null) return null;
        StringBuilder text = new StringBuilder(fresh ? "推荐了三个短信模版：" : "可选的短信模版：");
        for (int i = 0; i < choices.size(); i++) text.append("\n").append(i + 1).append(". ").append(choices.get(i));
        return text.toString();
    }

    @Override protected String askItem(String label, TaskFrame task) {
        return ActivityData.candidates(task.getSlots()).size() == 3
                ? "短信模版（回复1、2或3选择上面的一条，或者换个短信场景）"
                : "短信内容或使用场景（例如健康讲座通知），用来推荐三个短信模版";
    }

    @Override public String describe(Map<String, Object> slots) {
        List<String> parts = new ArrayList<>();
        if (slots.get("templateBrief") instanceof String brief && !brief.isBlank()) parts.add("短信场景：" + brief);
        int index = ActivityData.candidates(slots).indexOf(slots.get("smsContent"));
        if (index >= 0) parts.add("短信内容：第" + (index + 1) + "条模版");
        return parts.isEmpty() ? "（暂无）" : String.join("；", parts);
    }
}

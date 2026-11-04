package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeScope;
import com.example.smartflow.node.NodeSupport;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Component
public class ActivityTemplateNode extends ActivityCollectionNode {
    private static final String RECOMMEND_PROMPT = """
            你是社区短信文案助手。根据用户提供的短信原文或使用场景，推荐恰好三个不同的完整短信模版。
            保持用户原文事实、意图和已明确的活动信息，给出简洁、亲切、正式三种表达。
            不得编造地点、费用、优惠、联系电话、网址、报名方式或活动时间；缺失细节直接省略，不使用待填写占位符。
            每条1到300字。不要替用户选择。只输出JSON：{"templates":["第一条短信","第二条短信","第三条短信"]}
            """;
    public record Recommendations(List<String> templates) { }
    public ActivityTemplateNode(NodeSupport support) { super(support); }
    @Override public String id() { return MinshengNames.ACTIVITY_TEMPLATE; }
    @Override public String section() { return "template"; }
    @Override protected String label() { return "短信模版推荐与选择"; }
    @Override public Map<String, String> promptPreview() { return Map.of("recommend_templates · system", RECOMMEND_PROMPT); }
    @Override protected String question(TaskFrame task) {
        List<String> choices = ActivityData.candidates(task.getSlots());
        if (choices.size() != 3) return "请提供短信内容或使用场景，例如健康讲座通知；我会推荐三个模版供你选择。";
        return "请选择一个短信模版（回复1、2或3，也可以换个使用场景）：\n1. " + choices.get(0)
                + "\n2. " + choices.get(1) + "\n3. " + choices.get(2);
    }

    @Override protected String collect(TaskFrame task, ActivityInput input, NodeScope scope) {
        Map<String, Object> slots = task.getSlots();
        if (input.templateBrief() != null) {
            String brief = input.templateBrief().strip();
            if (brief.isEmpty() || brief.length() > 1000) return "请提供1到1000字的短信内容或使用场景。";
            // 更换场景立即撤销旧选择，生成失败也不能误用旧短信提交。
            slots.remove("smsContent"); slots.remove("templateCandidates");
            slots.put("templateBrief", brief);
            try {
                Recommendations result = chatJson(scope, "recommend_templates", support.models().chat(),
                        List.of(system(RECOMMEND_PROMPT), UserMessage.from("用户原文/场景：" + brief)), Recommendations.class);
                List<String> templates = result == null || result.templates() == null ? List.of() : result.templates();
                if (templates.size() != 3 || templates.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 300)
                        || new HashSet<>(templates).size() != 3)
                    return "暂时没能生成三个有效模版，请重新提供短信内容或场景。";
                slots.put("templateCandidates", new ArrayList<>(templates));
                // 同一句即使带“选1”，也是针对旧候选；新候选必须展示后另行选择。
                return null;
            } catch (LlmJsonException e) {
                return "短信模版推荐暂时失败，请重新提供场景重试。";
            }
        }
        if (input.templateChoice() != null) {
            List<String> choices = ActivityData.candidates(slots);
            int choice = input.templateChoice();
            if (choices.size() != 3 || choice < 1 || choice > 3) return "请从已展示的三个短信模版中选择1、2或3。";
            slots.put("smsContent", choices.get(choice - 1));
        }
        return null;
    }
}

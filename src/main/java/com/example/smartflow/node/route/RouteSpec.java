package com.example.smartflow.node.route;

import java.util.List;
import java.util.Objects;

/**
 * 子节点向父路由节点"自我介绍"的信息。父节点的提示词里"有哪些去向"这一段完全由这些信息拼出来，
 * 所以新增子节点时只需要在子节点里声明 RouteSpec，父节点的提示词和代码都不用改。
 *
 * @param parent      挂在哪个路由节点下（路由节点的 id）
 * @param intent      意图编码，模型输出这个值，同一父节点下唯一，建议大写下划线
 * @param name        中文名，用于提示词和给用户的提示（"刚才的活动创建还没完成"）
 * @param description 什么情况下应该路由到这里。写清楚边界，这是影响路由准确率最关键的一段
 * @param examples    典型用户说法，2~5 条即可
 * @param taskType    非空表示这是一个多轮任务的入口节点：路由到这里会开启一个任务并压栈；
 *                    为空表示一次性节点（回答完就结束，不压栈）
 * @param fallback    兜底节点：模型输出的意图不认识或解析失败时去这里。每个父节点必须有且只有一个
 * @param order       在提示词里的排列顺序，越小越靠前
 */
public record RouteSpec(String parent,
                        String intent,
                        String name,
                        String description,
                        List<String> examples,
                        String taskType,
                        boolean fallback,
                        int order) {

    public RouteSpec {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public boolean isTaskEntry() {
        return taskType != null;
    }

    /** 一次性节点：业务查询、闲聊等 */
    public static RouteSpec oneShot(String parent, String intent, String name, String description,
                                    List<String> examples, int order) {
        return new RouteSpec(parent, intent, name, description, examples, null, false, order);
    }

    /** 多轮任务的入口节点，taskType 与 intent 相同 */
    public static RouteSpec taskEntry(String parent, String intent, String name, String description,
                                      List<String> examples, int order) {
        return new RouteSpec(parent, intent, name, description, examples, intent, false, order);
    }

    /** 兜底节点 */
    public static RouteSpec fallback(String parent, String intent, String name, String description,
                                     List<String> examples, int order) {
        return new RouteSpec(parent, intent, name, description, examples, null, true, order);
    }
}

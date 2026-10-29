package com.example.smartflow.web;

import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.route.IntentRouterNode;
import com.example.smartflow.node.route.RouteCatalog;
import com.example.smartflow.node.route.RouteCatalog.RouteOption;
import com.example.smartflow.node.route.RouteSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调试用：当前注册了哪些节点、谁挂在谁下面、每个节点实际使用的提示词。
 * 提示词每次请求都实时生成，和运行时发给模型的是同一段代码产出的。
 */
@RestController
@RequestMapping("/api")
public class NodeController {

    private final RouteCatalog catalog;

    public NodeController(RouteCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/nodes")
    public Map<String, Object> nodes() {
        List<AbstractNode> nodes = catalog.nodes();
        Set<String> attached = new HashSet<>();

        // 路由节点及其子节点
        List<Map<String, Object>> routers = new ArrayList<>();
        for (AbstractNode node : nodes) {
            if (!(node instanceof IntentRouterNode router)) {
                continue;
            }
            String prompt = router.systemPrompt();
            List<Map<String, Object>> children = new ArrayList<>();
            for (RouteOption o : catalog.options(router.id())) {
                attached.add(o.nodeId());
                Map<String, Object> c = specToMap(o.spec());
                c.put("nodeId", o.nodeId());
                // 子节点的意图是否真的出现在父节点提示词里
                c.put("inPrompt", prompt.contains("- " + o.spec().intent() + "（"));
                children.add(c);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", router.id());
            r.put("children", children);
            routers.add(r);
        }

        // 每个节点的详情
        List<Map<String, Object>> list = new ArrayList<>();
        for (AbstractNode node : nodes) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.id());
            n.put("type", node.type().name());
            n.put("className", node.getClass().getSimpleName());
            n.put("router", node instanceof IntentRouterNode);
            n.put("route", node.routeSpec().map(NodeController::specToMap).orElse(null));
            n.put("stageDescription", node.stageDescription());
            n.put("prompts", promptsOf(node));
            list.add(n);
        }

        // 既不是路由节点、也没挂到任何路由下的节点：任务的后续阶段，只能由上一阶段推进过来
        List<String> unattached = nodes.stream()
                .filter(n -> !(n instanceof IntentRouterNode) && !attached.contains(n.id()))
                .map(AbstractNode::id)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("routers", routers);
        out.put("unattached", unattached);
        out.put("nodes", list);
        return out;
    }

    private static Map<String, String> promptsOf(AbstractNode node) {
        try {
            return node.promptPreview();
        } catch (Exception e) {
            // 一个节点的预览出错不影响整个页面
            return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> specToMap(RouteSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parent", s.parent());
        m.put("intent", s.intent());
        m.put("name", s.name());
        m.put("kind", s.fallback() ? "FALLBACK" : s.isTaskEntry() ? "TASK_ENTRY" : "ONE_SHOT");
        m.put("taskType", s.taskType());
        m.put("description", s.description());
        m.put("examples", s.examples());
        m.put("order", s.order());
        return m;
    }
}

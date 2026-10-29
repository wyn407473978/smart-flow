package com.example.smartflow.node.route;

import com.example.smartflow.node.AbstractNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 所有节点及其路由声明的目录。
 * <p>
 * 节点列表是懒加载的：路由节点本身也是节点，如果在构造时就注入全部节点会形成循环依赖，
 * 所以这里第一次用到时才去取。
 */
@Component
public class RouteCatalog {

    /** 某个父节点下的一个可选去向 */
    public record RouteOption(String nodeId, RouteSpec spec) {
    }

    private final Supplier<List<AbstractNode>> nodeSupplier;
    private volatile Snapshot snapshot;

    @Autowired
    public RouteCatalog(ObjectProvider<AbstractNode> nodes) {
        this(() -> nodes.orderedStream().toList());
    }

    /** 测试或非 Spring 环境使用 */
    public RouteCatalog(Supplier<List<AbstractNode>> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    // ------------------------------------------------------------------ 查询

    /** 某个路由节点下的全部去向，按 order 排序 */
    public List<RouteOption> options(String parent) {
        return snapshot().byParent.getOrDefault(parent, List.of());
    }

    public Optional<RouteOption> find(String parent, String intent) {
        if (intent == null) {
            return Optional.empty();
        }
        String key = intent.trim().toUpperCase();
        return options(parent).stream().filter(o -> o.spec().intent().equalsIgnoreCase(key)).findFirst();
    }

    public RouteOption fallback(String parent) {
        return options(parent).stream().filter(o -> o.spec().fallback()).findFirst()
                .orElseThrow(() -> new IllegalStateException("路由节点 " + parent + " 没有兜底子节点"));
    }

    /** 任务类型的中文名，例如 ACTIVITY_CREATE → 活动创建 */
    public String taskName(String taskType) {
        RouteOption entry = snapshot().taskEntries.get(taskType);
        if (entry != null) {
            return entry.spec().name();
        }
        // 临时追问帧的 taskType 是一次性节点的意图编码，例如 BIZ_QUERY → 业务查询
        return snapshot().byParent.values().stream().flatMap(List::stream)
                .filter(o -> o.spec().intent().equalsIgnoreCase(taskType))
                .map(o -> o.spec().name())
                .findFirst().orElse(taskType);
    }

    /** 节点当前阶段的说明，路由节点会把它告诉模型 */
    public String stageDescription(String nodeId) {
        AbstractNode node = snapshot().nodes.get(nodeId);
        return node == null ? "" : node.stageDescription();
    }

    public boolean exists(String nodeId) {
        return snapshot().nodes.containsKey(nodeId);
    }

    public List<AbstractNode> nodes() {
        return List.copyOf(snapshot().nodes.values());
    }

    /** 丢弃缓存，下次使用时重新读取节点列表（测试用；以后路由描述改成从后台配置读取时也用得上） */
    public void refresh() {
        snapshot = null;
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 启动时调用，配置有问题直接启动失败，而不是等到线上某一轮对话才发现。
     */
    public void validate() {
        Snapshot s = snapshot();
        for (Map.Entry<String, List<RouteOption>> e : s.byParent.entrySet()) {
            String parent = e.getKey();
            if (!s.nodes.containsKey(parent)) {
                throw new IllegalStateException("节点 " + e.getValue().get(0).nodeId() + " 声明的父节点 " + parent + " 不存在");
            }
            long fallbacks = e.getValue().stream().filter(o -> o.spec().fallback()).count();
            if (fallbacks != 1) {
                throw new IllegalStateException("路由节点 " + parent + " 必须有且只有一个兜底子节点，当前 " + fallbacks + " 个");
            }
            Set<String> intents = new HashSet<>();
            for (RouteOption o : e.getValue()) {
                if (!intents.add(o.spec().intent().toUpperCase())) {
                    throw new IllegalStateException("路由节点 " + parent + " 下意图编码重复: " + o.spec().intent());
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (this) {
                s = snapshot;
                if (s == null) {
                    s = build(nodeSupplier.get());
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private static Snapshot build(List<AbstractNode> nodes) {
        Map<String, AbstractNode> byId = new LinkedHashMap<>();
        Map<String, List<RouteOption>> byParent = new HashMap<>();
        Map<String, RouteOption> taskEntries = new HashMap<>();
        for (AbstractNode node : nodes) {
            if (byId.put(node.id(), node) != null) {
                throw new IllegalStateException("节点 id 重复: " + node.id());
            }
            node.routeSpec().ifPresent(spec -> {
                RouteOption option = new RouteOption(node.id(), spec);
                byParent.computeIfAbsent(spec.parent(), k -> new ArrayList<>()).add(option);
                if (spec.isTaskEntry() && taskEntries.put(spec.taskType(), option) != null) {
                    throw new IllegalStateException("任务类型 " + spec.taskType() + " 有多个入口节点");
                }
            });
        }
        byParent.replaceAll((k, v) -> v.stream()
                .sorted(Comparator.comparingInt((RouteOption o) -> o.spec().order()).thenComparing(RouteOption::nodeId))
                .toList());
        return new Snapshot(byId, byParent, taskEntries);
    }

    private record Snapshot(Map<String, AbstractNode> nodes,
                            Map<String, List<RouteOption>> byParent,
                            Map<String, RouteOption> taskEntries) {
    }
}

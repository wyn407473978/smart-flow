package com.example.smartflow.config;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.node.AbstractNode;
import com.example.smartflow.node.route.RouteCatalog;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 工作流图：根据 Spring 容器里的全部节点自动组装，新增节点不用改这里。
 * <p>
 * 所有节点共用同一条条件边：读状态里的 {@code nextNode}。
 * 路由节点把它设成路由目标，任务阶段节点在推进阶段时把它设成下一阶段，其余情况基类默认设成结束本轮。
 *
 * <pre>
 * START → router → (nextNode) → 某节点 → (nextNode) → … → END
 * </pre>
 */
@Configuration
public class GraphConfig {

    @Bean
    public CompiledGraph<FlowState> workflowGraph(List<AbstractNode> nodes, RouteCatalog catalog) throws GraphStateException {
        return build(nodes, catalog);
    }

    public static CompiledGraph<FlowState> build(List<AbstractNode> nodes, RouteCatalog catalog) throws GraphStateException {
        catalog.validate();

        Map<String, String> targets = new HashMap<>();
        for (AbstractNode node : nodes) {
            targets.put(node.id(), node.id());
        }
        targets.put(FlowNames.END_TURN, END);
        if (!targets.containsKey(FlowNames.ROUTER)) {
            throw new IllegalStateException("缺少根路由节点 " + FlowNames.ROUTER);
        }

        StateGraph<FlowState> graph = new StateGraph<>(FlowState.SCHEMA, FlowState::new);
        for (AbstractNode node : nodes) {
            graph.addNode(node.id(), node_async(node));
        }
        graph.addEdge(START, FlowNames.ROUTER);
        for (AbstractNode node : nodes) {
            String from = node.id();
            graph.addConditionalEdges(from, edge_async(state -> {
                String next = state.nextNode();
                if (!targets.containsKey(next)) {
                    throw new IllegalStateException("节点 " + from + " 指定的下一个节点不存在: " + next);
                }
                return next;
            }), targets);
        }
        return graph.compile();
    }
}

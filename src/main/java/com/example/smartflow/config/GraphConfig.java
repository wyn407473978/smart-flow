package com.example.smartflow.config;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.flow.FlowState;
import com.example.smartflow.flow.RouteDecision;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.ChatNode;
import com.example.smartflow.node.ChooseTemplatePlaceholderNode;
import com.example.smartflow.node.CollectTargetNode;
import com.example.smartflow.node.RouterNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 工作流图定义。每轮对话都从 START 跑到 END：
 *
 * <pre>
 * START → router ─┬→ collect_target ─(收齐)→ choose_template → END
 *                 │        └─(没收齐)→ END
 *                 ├→ choose_template → END
 *                 ├→ chat → END
 *                 └→ END（例如取消任务，路由节点已经回复）
 * </pre>
 */
@Configuration
public class GraphConfig {

    @Bean
    public CompiledGraph<FlowState> workflowGraph(RouterNode router,
                                                  ChatNode chat,
                                                  CollectTargetNode collectTarget,
                                                  ChooseTemplatePlaceholderNode chooseTemplate) throws GraphStateException {

        return new StateGraph<>(FlowState.SCHEMA, FlowState::new)
                .addNode(router.id(), node_async(router))
                .addNode(chat.id(), node_async(chat))
                .addNode(collectTarget.id(), node_async(collectTarget))
                .addNode(chooseTemplate.id(), node_async(chooseTemplate))

                .addEdge(START, router.id())

                // 路由：去向完全由 RouterNode 写进状态的 RouteDecision.target 决定
                .addConditionalEdges(router.id(),
                        edge_async(state -> state.route().map(RouteDecision::target).orElse(FlowNames.CHAT)),
                        Map.of(
                                FlowNames.COLLECT_TARGET, FlowNames.COLLECT_TARGET,
                                FlowNames.CHOOSE_TEMPLATE, FlowNames.CHOOSE_TEMPLATE,
                                FlowNames.CHAT, FlowNames.CHAT,
                                FlowNames.END_TURN, END))

                // C1 收齐参数后任务的 currentNode 会被推进到 C2，同一轮内直接进入 C2
                .addConditionalEdges(collectTarget.id(),
                        edge_async(state -> state.activeTask()
                                .map(TaskFrame::getCurrentNode)
                                .filter(FlowNames.CHOOSE_TEMPLATE::equals)
                                .orElse(FlowNames.END_TURN)),
                        Map.of(
                                FlowNames.CHOOSE_TEMPLATE, FlowNames.CHOOSE_TEMPLATE,
                                FlowNames.END_TURN, END))

                .addEdge(chooseTemplate.id(), END)
                .addEdge(chat.id(), END)
                .compile();
    }
}

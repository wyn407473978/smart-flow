package com.example.smartflow.node;

import com.example.smartflow.flow.FlowNames;
import com.example.smartflow.node.route.IntentRouterNode;
import com.example.smartflow.node.route.RouteCatalog;
import org.springframework.stereotype.Component;

/**
 * A 节点：全局路由，每轮必经。
 * <p>
 * 这里没有任何关于子节点和具体业务的内容：助手名字来自 flow.assistant.name，
 * 子节点通过 {@link AbstractNode#routeSpec()} 声明 parent = "router" 就会自动出现在路由提示词和去向里。
 */
@Component
public class RouterNode extends IntentRouterNode {

    public RouterNode(NodeSupport support, RouteCatalog catalog) {
        super(support, catalog);
    }

    @Override
    public String id() {
        return FlowNames.ROUTER;
    }
}

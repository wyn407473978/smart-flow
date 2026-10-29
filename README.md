# smart-flow：智慧民生多节点对话工作流（最小闭环）

Spring Boot 3.5 + LangChain4j 1.19 + LangGraph4j 1.8（LTS）+ PostgreSQL。

## 已实现

| 节点 | ID | 类型 | 说明 |
|---|---|---|---|
| A 路由 | `router` | ROUTER | 每轮必经。提示词由子节点的路由声明自动拼成；模型判断 action + intent，去向和任务栈变化由代码决定 |
| B2 业务查询 | `biz_query` | TOOL_AGENT | 挂载 `get_weather`（查天气，演示数据）、`get_user_info`（查本人信息：rise / 27 岁）；手写 tool loop，流式输出 |
| B3 闲聊 | `chat` | CHAT | 流式回复；根路由的兜底节点 |
| C1 目标人群 | `collect_target` | SLOT_FILLING | 抽取年龄 / 性别 / 区域 → 代码校验合并 → 收齐则同一轮进入 C2，否则流式追问 |
| C2 模版确认 | `choose_template` | PLACEHOLDER | 占位节点，用来验证 C1→C2 同轮流转 |

支持的对话行为：开始任务、多轮补充、修改已填信息、任务中插话（回答后提示继续）、取消任务、路由输出异常兜底。

## 快速开始

```bash
# 1. 建库（表会在启动时自动创建，脚本在 src/main/resources/db/schema.sql）
createdb smart_flow

# 2. 配置模型 Key（默认走阿里云百炼的 OpenAI 兼容端点，可在 application.yml 改成任意兼容端点）
export DASHSCOPE_API_KEY=sk-xxx

# 3. 启动
mvn spring-boot:run

# 4. 打开测试页面
open http://localhost:8080/
```

数据库地址、账号、模型名都在 `application.yml` 里改。

非流式接口（方便 curl 和自动化评测）：

```bash
curl -s localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","message":"我想办一个活动"}'
```

## 目录结构

```
config/      模型、图定义、配置项
flow/        图状态 FlowState、任务帧 TaskFrame、路由结果、常量
node/        AbstractNode 基类 + 各节点
engine/      WorkflowEngine（每轮入口）、轮次上下文、推送事件
session/     会话状态、消息存取
trace/       轮次 / 节点 / 模型调用 / 工具调用 记录；ChatModelListener
web/         SSE 接口、调试查询接口
resources/static/index.html   测试页面
```

## 一轮对话怎么跑

1. 加会话锁，读出 `flow_session.state`（任务栈、摘要）和最近 N 条消息
2. 作为输入从 START 跑一遍图：`router → 目标节点 → … → END`
3. 节点通过 `AbstractNode` 自动记录执行、推送事件、调用模型
4. 拼接各节点回复，插话时追加"要继续吗"，写回会话状态、消息、轮次记录

跨轮状态由引擎自己存取，不依赖 LangGraph4j 的 checkpoint 语义；图每轮都完整跑一次，所以插话、取消、跳转都在路由节点里统一处理。

## 新增一个节点（父节点不用改）

新增子节点只需要写一个类，`RouterNode`、它的提示词、`GraphConfig` 都不用动：

```java
@Component
public class ComplaintNode extends AbstractNode {
    public ComplaintNode(NodeSupport support) { super(support); }
    @Override public String id() { return "complaint"; }
    @Override public NodeType type() { return NodeType.CHAT; }

    // 向父路由"自我介绍"：路由提示词里的去向列表就是由这些声明拼出来的
    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.oneShot(FlowNames.ROUTER, "COMPLAINT", "投诉建议",
                "用户要投诉、反映问题或提建议",
                List.of("楼道灯坏了没人修", "我要投诉物业"), 30));
    }

    @Override protected Map<String, Object> execute(FlowState state, NodeScope scope) { ... }
}
```

`RouteSpec` 三种：

| 工厂方法 | 含义 | 例子 |
|---|---|---|
| `oneShot` | 一次性节点，回答完就结束；任务进行中被路由到算插话，回答后提示继续任务 | 业务查询、投诉 |
| `taskEntry` | 多轮任务的入口，路由到这里会开启任务并压栈 | 活动创建（C1） |
| `fallback` | 兜底，意图不认识或模型输出异常时去这里，每个路由节点有且只有一个 | 闲聊 |

任务内部的后续阶段（C2、C3）不声明 `routeSpec`，它们只能由上一阶段推进过来：在上一阶段里 `task.setCurrentNode(下一阶段)` 并 `goTo(updates, 下一阶段)`。
任务阶段节点可以覆盖 `stageDescription()`，告诉路由模型"现在在等用户提供什么"，提高短回答（例如"女性"）判成 CONTINUE 的准确率。

自动完成的事情：
- 路由提示词的模块列表、兜底意图（`IntentRouterNode.systemPrompt()` 可以直接打印出来看）
- 意图 → 节点、任务类型 → 入口节点的映射
- 图的节点和边：所有节点共用一条读 `nextNode` 的条件边
- 闲聊节点的"我能帮你做什么"、插话后的"刚才的 xx 还没完成"、取消时的"已取消 xx"
- 启动校验：意图编码重复、兜底节点不是恰好一个、父节点不存在、任务入口重复，都会直接启动失败

节点通用约定：
1. 需要调模型时用基类方法：`chatJson(...)`（结构化输出，自带一次纠错重试）、`streamReply(...)`（流式回复）、`emitText(...)`（固定文案）
2. 返回值里只放要更新的状态 key；回复放 `FlowState.REPLIES`
3. 放进状态的对象必须实现 `Serializable`（LangGraph4j 在节点间会克隆状态）；从状态取出的 `TaskFrame` 先 `copy()` 再改

**怎么确认子节点注册成功**：打开测试页面的「节点 / 提示词」页签（接口 `GET /api/nodes`）。
- 「注册关系」表列出每个路由节点下挂了哪些子节点，✓ 表示该子节点的意图已经出现在父节点的提示词里
- 下面是每个节点当前实际使用的提示词，路由提示词里子节点的意图编码会高亮；每次切换到这个页签或点「刷新」都会重新生成
- 节点通过覆盖 `promptPreview()` 暴露自己的提示词，运行时才知道的内容用 `{占位符}` 表示

以后某个分支下子节点多了需要二级路由（例如 B1 下有多种任务），继承 `IntentRouterNode` 换个 id，子节点把 `parent` 指向它即可，同样不用改提示词。

## 工具节点

`BizQueryNode` 是带工具节点的样板，没有用 `@Tool` + AiServices，而是：

1. 每个工具实现 `FlowTool`：显式给出 `ToolSpecification`（名字、描述、参数 schema）和 `execute(args, context)`
2. 节点调用基类的 `streamWithTools(scope, purpose, messages, tools, maxRounds)`：
   把工具定义发给模型 → 模型要调工具就逐个执行、结果追加进对话 → 直到模型给出最终回答（最多 `maxRounds` 轮，防死循环）
3. 每次工具调用落 `flow_tool_call`（参数、结果、耗时、状态，并通过 `llm_call_id` 关联到发起它的那次模型调用），同时推送 `tool` 事件，测试页实时事件和轮次详情里都能看到
4. 工具不存在或抛异常时，把错误信息作为工具结果返回给模型，由模型如实告诉用户，不会中断整轮对话

**追问用户**：`streamWithTools` 会自动给模型挂一个内置工具 `ask_user`。模型缺参数或需要确认时调用它，而不是在回复里直接提问，节点据此调用 `awaitAnswer(...)`：

- 在任务栈顶放一个**临时追问帧**（`ephemeral=true`，记录追问原话），路由提示词里会出现「待回答的问题：来自"业务查询"：……」
- 下一轮用户的回答（"是的""杭州"）被路由回提问的节点；用户没理追问、换了话题或继续原任务，追问帧自动作废
- 追问期间不追加"刚才的 xx 还没完成，要继续吗"，避免一条回复里出现两个问题；追问结束、插话处理完以后才提示
- 路由判 CONTINUE 时用模型给的 intent 区分：intent 是进行中任务的就继续任务，否则视为回答追问

新增工具：写一个 `FlowTool` 实现类（`@Component`），注入到节点的工具列表里。如果它扩大了节点的能力范围，同步更新节点 `routeSpec()` 的描述和示例。

## 记录了什么

| 表 | 内容 |
|---|---|
| `flow_turn` | 每轮的输入、最终回复、路由结果、节点路径、执行前后状态、耗时、异常 |
| `flow_node_exec` | 每个节点的输入状态、输出更新、耗时、异常 |
| `flow_llm_call` | 每次模型调用：合并默认参数后的完整请求、完整响应、token、耗时；通过 `exec_id` 关联到节点 |
| `flow_tool_call` | 每次工具调用：参数、结果、耗时、状态；通过 `llm_call_id` 关联到发起它的模型调用 |
| `flow_message` / `flow_session` | 对话消息、会话状态 |

模型调用是通过 LangChain4j 的 `ChatModelListener` + `ChatRequestOptions.listenerAttributes` 记录的，流式回调在别的线程也能关联到正确的节点。需要看原始 HTTP 报文时打开 `flow.model.log-http`。

## 测试

```bash
mvn test
```

- `CollectTargetNodeTest`：槽位校验 / 合并逻辑的纯单元测试
- `WorkflowScenarioTest`：多轮场景测试，用按脚本返回的 stub 模型 + 内存存储，不需要数据库和真实模型。覆盖开始任务、补充、插话、非法 JSON 重试、同轮流转 C2、取消、路由兜底

这两类测试就是后续 Evals 的起点：场景脚本保留，把 stub 换成真实模型 + LLM 扮演用户，就是端到端评测。

## 下一步

- C2：换成 TOOL_AGENT 节点，挂"查询可用模版"工具，手写 tool loop 并落 `flow_tool_call`
- C3：二次确认节点，确认后由代码调用创建接口，带幂等键
- 会话锁换 Redis；trace 改为异步批量写
- 早期对话摘要（`summary` 字段已预留）

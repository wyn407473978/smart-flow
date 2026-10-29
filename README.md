# smart-flow：智慧民生多节点对话工作流（最小闭环）

Spring Boot 3.5 + LangChain4j 1.19 + LangGraph4j 1.8（LTS）+ PostgreSQL。

## 已实现

| 节点 | ID | 类型 | 说明 |
|---|---|---|---|
| A 路由 | `router` | ROUTER | 每轮必经。模型判断 action + intent，去向和任务栈变化由代码决定 |
| B3 闲聊 | `chat` | CHAT | 流式回复；业务查询（B2）未接入前也落到这里 |
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

## 新增一个节点

1. 继承 `AbstractNode`，实现 `id()`、`type()`、`execute()`，加 `@Component`
2. 需要调模型时用基类方法：`chatJson(...)`（结构化输出，自带一次纠错重试）、`streamReply(...)`（流式回复）、`emitText(...)`（固定文案）
3. 返回值里只放要更新的状态 key；回复放 `FlowState.REPLIES`
4. 在 `GraphConfig` 里 `addNode` 并连边；如果是路由可达的节点，在 `RouterNode` 的去向规则和条件边映射里加上

注意：放进状态的对象必须实现 `Serializable`（LangGraph4j 在节点间会克隆状态）；从状态取出的 `TaskFrame` 先 `copy()` 再改。

## 记录了什么

| 表 | 内容 |
|---|---|
| `flow_turn` | 每轮的输入、最终回复、路由结果、节点路径、执行前后状态、耗时、异常 |
| `flow_node_exec` | 每个节点的输入状态、输出更新、耗时、异常 |
| `flow_llm_call` | 每次模型调用：合并默认参数后的完整请求、完整响应、token、耗时；通过 `exec_id` 关联到节点 |
| `flow_tool_call` | 已建表，B2 / C2 接入工具时使用 |
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
- B2：业务查询节点，`RouterNode.targetFor` 里把 BIZ_QUERY 指过去
- 会话锁换 Redis；trace 改为异步批量写
- 早期对话摘要（`summary` 字段已预留）

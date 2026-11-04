# Demo：创建短信活动

修改短信活动，从 `smart-flow-demo/src/main/java/com/example/smartflow/demo/activity/` 开始。
业务查询、闲聊及原来的工具保留；框架 core 的路由、任务栈、记忆和 trace 无需修改。

```text
router（原来的根路由）
├── biz_query   业务查询，保留天气和用户信息工具
├── chat        闲聊
└── ACTIVITY_CREATE  ActivityCreateRouterNode：短信活动路由
    ├── activity_region    ActivityRegionNode：发送区域
    ├── activity_audience  ActivityAudienceNode：发送人群
    ├── activity_time      ActivityTimeNode：发送时间
    ├── activity_template  ActivityTemplateNode：推荐三个短信模版、等待选择
    └── activity_submit    ActivitySubmitNode：四项收齐且有效 → 汇总 → 用户确认 → mock接口 → 完成
```

前四个收集节点没有业务先后顺序。用户可以先提供时间，也可以先选模版，或一句提供多个参数。
如果一句提到多项，路由会逐个交给对应子节点处理；这只是本轮内部执行顺序，不要求用户按这个顺序填写。
没有参数可处理时才追问一项缺失信息。每次追问会展示已保存的参数，避免用户不知道系统记住了什么。

## 常见修改入口

| 需求 | 修改位置 |
|---|---|
| 什么话进入短信活动、怎么理解日期/选择编号 | `ActivityCreateRouterNode.routeSpec()` / `systemPrompt()` |
| 区域处理和校验 | `ActivityRegionNode.collect()` |
| 全部人群、性别、年龄规则 | `ActivityAudienceNode.collect()` |
| 时间校验 | `ActivityTimeNode.collect()`、`ActivityData.future()` |
| 推荐三个短信模版的文案要求 | `ActivityTemplateNode.RECOMMEND_PROMPT` |
| 最终展示哪些参数 | `ActivityData.summary()` |
| 接真实业务接口 | `MockSmsActivityClient.create()`，保留 `requestId` 幂等语义 |
| 提交成功文案 | `ActivitySubmitNode.commit()` |

路由只抽取本轮结构化输入，子节点负责校验和保存自己的一组参数。
四个节点共享同一个 `TaskFrame.slots`，没有四个独立任务，也不会因为切换收集节点而清空其他参数。
本轮输入、待处理分组、校验错误仅保存在图状态；`waitingSection`、模版候选和已选短信随任务持久化。
追问结束时，任务的 `currentNode` 回到活动路由，所以下轮可以回答任意一项，而不是锁定在上次追问的节点。

## 规则

- 区域保留用户原话，例如“杭州市西湖区蒋春街道”。Demo 不连接行政区划服务，不擅自更名或补造地址。
- 人群可以只说“男性”或“20岁以上”，未限制的维度默认不限；“全部人群”清除旧性别和年龄条件。
  年龄须在0到120之间，下限不得大于上限；新的年龄范围整体替换旧范围，校验失败保留旧条件并继续等待修改。
- 时间按北京时间 `Asia/Shanghai` 理解。路由会把当前时间传给模型，用于“明天9点”等相对时间的转换。
  保存格式为 `yyyy-MM-dd HH:mm:ss`，代码严格检查真实日期，且必须晚于当前时间。
  只有“明天”“下周六上午”时追问具体时刻，不自动定为9点。最终确认和调用接口前都会重新校验。
- 提供短信原文或场景后，模型生成恰好三个不同的完整候选。用户必须明确选定其中一个，不能由模型自动代选。
  更换场景会清除旧候选和旧选择；新候选必须先展示，再让用户选择。候选和选定的实际短信正文都会保存。
- 只有四项有效、没有未完成修改时，才展示提交确认。第一次进入提交节点只展示汇总，不会立即调用接口。
  修改后会重新确认；用户明确确认后调用 mock，成功结束任务，失败保留任务、用同一请求号重试。
- `MockSmsActivityClient` 是进程内 mock，返回活动编号并保存请求快照；相同任务请求号只创建一次。
  重启会清空 mock 数据，不会发送真实短信。真实接口应在服务端持久化幂等键。

## 试用对话

```text
用户：创建一个短信活动，明天上午9点发，健康讲座通知
助手：记录发送时间，推荐三个短信模版；追问剩余信息
用户：给杭州市西湖区蒋村街道20岁以上的男性
助手：记录区域和人群，展示三个短信模版，请用户选1、2或3
用户：选第二个
助手：展示区域、人群、具体发送时间和短信正文，请用户确认
用户：区域改成杭州市拱墅区
助手：更新区域，重新展示汇总并请用户确认
用户：确认
助手：短信活动创建完毕，活动编号 SMS-…，计划发送时间 …（北京时间）。
```

如果在收集期间询问天气或闲聊，仍走原来的 `biz_query` / `chat`，活动参数保留；说“继续”可回到活动。
确认前也可以取消，取消后不会调用 mock。

测试页快捷消息已同步更新。「节点 / 提示词」页签会同时显示根路由和活动子路由。

## 旧会话与测试

旧的 `collect_target`、`choose_template`、`submit_activity` ID 由 `ActivityLegacyConfiguration` 接回新活动路由，保留旧槽位。
旧的自然语言时间及活动类型枚举不能直接当作具体发送时间、短信正文提交；用户需要补充未来的具体时间、确认人群并选择短信。
旧节点实现移到测试目录，仅用于原有框架回归场景，不参与 Spring 节点注册。

`ActivityWorkflowTest` 使用实际图、实际根路由和脚本模型验证多轮流转，覆盖全部24种收集顺序、同句多参数、
三选一、时间校验及过期、修改后重确认、取消、插话、mock失败重试与幂等。它不依赖数据库或真实模型。

```bash
mvn test
```

# Demo：创建短信活动

修改短信活动，从 `smart-flow-demo/src/main/java/com/example/smartflow/demo/activity/` 开始。
业务查询、闲聊及原来的工具保留。四个收集节点都是框架的 `SlotFillingNode`，由框架的 `SlotGroupNode` 组成一个无序组，
demo 只声明要收集什么、业务规则和提交方式。

```text
router（根路由）
├── biz_query   业务查询，保留天气和用户信息工具
├── chat        闲聊
└── ACTIVITY_CREATE  ActivityCreateNode（SlotGroupNode）：短信活动收集组
    │  成员（SlotFillingNode，没有先后顺序）
    ├── activity_region    ActivityRegionNode：发送区域
    ├── activity_audience  ActivityAudienceNode：发送人群（性别、年龄上下限，组成“发送人群”组）
    ├── activity_time      ActivityTimeNode：发送时间
    └── activity_template  ActivityTemplateNode：推荐三个短信模版、等待选择
    └─→ activity_submit    ActivitySubmitNode（ConfirmNode）：四项收齐且有效 → 汇总 → 用户确认 → mock接口 → 完成
```

用户可以先提供时间，也可以先说短信场景，或一句提供多个参数。每轮的处理：

1. `ActivityCreateNode` 用四个成员声明的全部槽位做**一次**抽取（带当前北京时间和最近 6 条对话）。
2. 本轮提到的成员各自用自己的声明和规则校验、合并（`SlotFillingNode.collect`）：类型和范围、`normalize`、
   `validateTogether`，以及 `afterMerge`（模版节点在这里推荐三个候选、写入用户选定的正文）。
3. 四项都有效就进入 `activity_submit`；否则统一追问，规则和单个 `SlotFillingNode` 一致：**说什么由代码决定，模型只负责措辞**。
   - 代码先输出本轮记下的参数（例如“当前已记录：发送区域：杭州市西湖区蒋村街道”）和校验提示，不再每轮打印整张参数表；
   - 本轮刚推荐的三个短信模版，或者这一轮要用户选模版时，由代码原文列出（`choicesText`），追问模型不复述、不改写；
   - 问哪几项由代码决定：有要重新提供的项（校验失败、要求修改、发送时间过期）时只问这些；否则先请用户选模版，再按区域、人群、时间、模版的顺序补缺；每轮最多两项；
   - 追问模型（`ask_missing`）按计划写一两句话，能回应“我再想想”“你推荐一个”；同一个问题连续问两次时会换个说法。
     `flow.slot-filling.ask-mode: code`，或模型首字超时、出错时，改用代码文案（“请补充：发送区域（例如：…）、发送人群（…）。”）。

任务停在 `ACTIVITY_CREATE` 上，所以下轮可以回答任意一项。根路由能看到四个成员的可修改字段（`region`、`gender`、`ageMin`、`ageMax`、
`sendTime`、`templateBrief`、`smsContent`）；用户说“我要改区域”时，任务回到收集组追问新区域，旧值在新值通过校验前保留。

## 常见修改入口

| 需求 | 修改位置 |
|---|---|
| 什么话进入短信活动 | `ActivityCreateNode.routeSpec()` |
| 某项怎么抽取（字段说明、常见说法） | 对应成员 `slots()` 里的 `SlotSpec.describe(...)` |
| 区域长度等校验 | `ActivityRegionNode.slots()` |
| 全部人群、单边年龄范围、上下限校验 | `ActivityAudienceNode.normalize()` / `validateTogether()` |
| 时间校验、过期判断 | `ActivityTimeNode.validateTogether()` / `revalidate()` |
| 推荐三个短信模版、选择编号 | `ActivityTemplateNode.RECOMMEND_PROMPT` / `afterMerge()` |
| 候选怎么展示、某项交给追问模型的说明、记下后怎么描述 | 成员的 `choicesText()` / `askItem()` / `describe()` |
| 追问顺序、每轮问几项 | 框架 `SlotAsk`（单个收集节点和收集组共用） |
| 最终展示哪些参数 | `ActivityData.summary()` |
| 接真实业务接口 | `MockSmsActivityClient.create()`，保留 `requestId` 幂等语义 |
| 提交成功文案 | `ActivitySubmitNode.commit()` |

四个成员共享同一个 `TaskFrame.slots`，没有四个独立任务。模版候选（`templateCandidates`）和选定的正文（`smsContent`）随任务持久化；
`smsContent` 是 `managed` 槽位，不出现在抽取提示词里，模型输出也会被忽略，只能由代码从候选里写入。

## 规则

- 区域保留用户原话，例如“杭州市西湖区蒋春街道”。Demo 不连接行政区划服务，不擅自更名或补造地址。
- 人群可以只说“男性”或“20岁以上”，未限制的维度默认不限；“全部人群”清除旧性别和年龄条件。
  年龄须在0到120之间，下限不得大于上限。两端都给出或说的是“20岁以上”这类单边范围（`ageOpenEnded=true`）时整体替换旧范围；
  只调整一端（“上限改成70”）时保留另一端。校验失败保留旧条件并继续等待修改。
- 时间按北京时间 `Asia/Shanghai` 理解。抽取时会把当前时间和最近对话传给模型，用于“明天9点”等相对时间的转换。
  保存格式为 `yyyy-MM-dd HH:mm:ss`，代码严格检查真实日期，且必须晚于当前时间。
  只有“明天”“下周六上午”时追问具体时刻，不自动定为9点。提示用自然语言说明原因（还差时刻、日期不存在、已经过去），
  不向用户展示格式串。最终确认和调用接口前都会重新校验，原定时间过期时会明确告诉用户。
- 本轮输入整体无法识别时，提示“没能识别出活动信息”，不挂在某一项上，按正常顺序继续追问。
- 提供短信原文或场景后，模型生成恰好三个不同的完整候选。用户必须明确选定其中一个，不能由模型自动代选。
  更换场景会清除旧候选和旧选择；新候选必须先展示，再让用户选择。候选和选定的实际短信正文都会保存。
- 只有四项有效、没有未完成修改时，才展示提交确认。第一次进入提交节点只展示汇总，不会立即调用接口。
  修改后会重新确认；用户明确确认后调用 mock，成功结束任务，失败保留任务、用同一请求号重试。
- `MockSmsActivityClient` 是进程内 mock，返回活动编号并保存请求快照；相同任务请求号只创建一次。
  重启会清空 mock 数据，不会发送真实短信。真实接口应在服务端持久化幂等键。

## 试用对话

```text
用户：创建一个短信活动，明天上午9点发，健康讲座通知
助手：当前已记录发送时间和短信场景；列出刚推荐的三个短信模版；请用户选一个，并问发送区域
用户：给杭州市西湖区蒋村街道20岁以上的男性
助手：已记录区域和人群；再列出三个模版，请用户选1、2或3
用户：选第二个
助手：展示区域、人群、具体发送时间和短信正文，请用户确认
用户：区域改成杭州市拱墅区
助手：更新区域，重新展示汇总并请用户确认
用户：确认
助手：短信活动创建完毕，活动编号 SMS-…，计划发送时间 …（北京时间）。
```

如果在收集期间询问天气或闲聊，仍走原来的 `biz_query` / `chat`，活动参数保留；说“继续”可回到活动。
确认前也可以取消，取消后不会调用 mock。

测试页快捷消息已同步更新。「节点 / 提示词」页签能看到收集组的抽取和追问提示词，以及每个成员的槽位声明。

## 旧会话与测试

旧的 `collect_target`、`choose_template`、`submit_activity` ID 由 `ActivityLegacyConfiguration` 接回收集组，保留旧槽位；
旧的待修改字段 `activityTime`、`template` 换成 `sendTime`、`smsContent`。
旧的自然语言时间及活动类型枚举不能直接当作具体发送时间、短信正文提交；用户需要补充未来的具体时间、确认人群并选择短信。
旧节点实现移到测试目录，仅用于原有框架回归场景，不参与 Spring 节点注册。

`ActivityWorkflowTest` 使用实际图、实际根路由和脚本模型验证多轮流转，覆盖全部24种收集顺序、同句多参数、
三选一、模型不能直接填写正文、时间校验及过期、修改后重确认、取消、插话、mock失败重试与幂等，以及模型追问和兜底。
它不依赖数据库或真实模型。

```bash
mvn test
```

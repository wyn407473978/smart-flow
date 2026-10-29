# 用 smart-flow 搭一个新工作流

这篇以"物业报修"为例，从零搭一个工作流：用户说"我家水管漏了"，助手收集问题类型、地址、联系电话，
请用户确认后提交工单。闲聊、取消、任务中插话、修改信息这些通用能力都由框架完成，只需要写 3 个节点。

```
router（框架自带）
├── chat           兜底：闲聊和其他问题              ← 一次性节点
└── repair_collect 报修：收集问题、地址、电话          ← 任务入口（SlotFillingNode）
        └── repair_submit 确认后提交工单              ← 任务最后一个阶段（ConfirmNode）
```

## 1. 建工程

新建一个 Spring Boot 工程（或者在本仓库里加一个和 `smart-flow-demo` 平级的模块），依赖 `smart-flow-web`：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>smart-flow-web</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

不需要 HTTP 接口和测试页（例如只在 MQ 消费者里调用 `WorkflowEngine`）时，只依赖 `smart-flow-core`。

启动类放在自己的包里，不用扫描框架的包，框架 Bean 由自动配置注册：

```java
@SpringBootApplication
public class RepairApplication {
    public static void main(String[] args) {
        SpringApplication.run(RepairApplication.class, args);
    }
}
```

## 2. 配置

参考 `smart-flow-demo/src/main/resources/application.yml`，至少要配：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/repair
    username: xxx
    password: xxx
  sql:
    init:
      mode: always
      # 框架的表 + 自己业务的表
      schema-locations: classpath:db/smart-flow-schema.sql,classpath:db/repair.sql

flow:
  assistant:
    name: 物业小助手
    persona: 语气礼貌、简洁，用中文回答
  model:
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY:}
    chat-model: deepseek-chat
    router-model: deepseek-chat
```

`flow.memory`、`flow.sse` 都有默认值，需要时再调。

## 3. 兜底节点

每个路由节点下必须有且只有一个兜底节点（启动时会校验）。直接复制 demo 的 `ChatNode`，改一下 id、意图名和示例即可：

```java
@Override public Optional<RouteSpec> routeSpec() {
    return Optional.of(RouteSpec.fallback(FlowNames.ROUTER, "CHAT", "闲聊",
            "问候、闲聊、常识问答，以及其他模块都处理不了的问题",
            List.of("你好", "你是谁"), 1000));
}
```

提示词里的助手名字、语气从 `support.assistant()` 取，不要写死。

## 4. 任务入口：收集信息（SlotFillingNode）

只声明要收集什么；抽取、校验、追问、收齐后推进都由基类完成。

```java
@Component
public class RepairCollectNode extends SlotFillingNode {

    private static final List<SlotSpec> SLOTS = List.of(
            SlotSpec.enumOf("problem", "问题类型")
                    .option("WATER", "水管漏水").option("POWER", "停电跳闸")
                    .option("DOOR", "门窗损坏").option("OTHER", "其他")
                    .build(),
            SlotSpec.string("address", "地址").maxLength(100).describe("楼栋、单元、门牌号，保留用户原话").build(),
            SlotSpec.string("phone", "联系电话").maxLength(20).build(),
            SlotSpec.string("detail", "补充说明").optional().build());   // 可选项不会被追问

    public RepairCollectNode(NodeSupport support) { super(support); }

    @Override public String id() { return "repair_collect"; }

    /** 任务入口：路由识别到"报修"时开启任务，从这里开始 */
    @Override public Optional<RouteSpec> routeSpec() {
        return Optional.of(RouteSpec.taskEntry(FlowNames.ROUTER, "REPAIR", "报修",
                "用户家里或公共区域有东西坏了，需要物业上门维修",
                List.of("我家水管漏了", "楼道灯不亮了", "门锁坏了"), 10));
    }

    @Override protected List<SlotSpec> slots() { return SLOTS; }
    @Override protected String taskContext() { return "用户正在提交物业报修。"; }
    @Override protected String stageGoal() { return "收集报修的问题类型、地址和联系电话"; }
    @Override protected String nextNode() { return "repair_submit"; }   // 返回 null 表示收齐即完成

    /** 跨字段 / 业务规则校验，不合法的槽位保持原值，原因会在追问时告诉用户 */
    @Override protected void validateTogether(Map<String, Object> candidate, SlotErrors errors) {
        if (candidate.get("phone") instanceof String p && !p.matches("1\\d{10}")) {
            errors.reject("手机号格式不对，需要 11 位数字", "phone");
        }
    }
}
```

`SlotSpec` 能力：

| 写法 | 作用 |
|---|---|
| `string / integer / number / bool / enumOf` | 类型，模型的原始输出会按类型转换，转换失败的不写入 |
| `.range(min, max)` / `.min()` / `.max()` / `.maxLength()` | 取值范围、长度 |
| `.option(编码, 中文名)` | 枚举可选值；模型输出编码或中文名都可以，保存编码；追问时会把可选值告诉用户 |
| `.optional()` | 非必填，不会被追问 |
| `.group("年龄范围")` | 必填组：组里填了任意一个就算收齐，追问时说组名 |
| `.extractOnly()` | 只让模型抽取、不保存的辅助字段，在 `normalize` 里换算成真正的槽位 |
| `.describe("...")` | 给抽取模型的补充说明：取值规则、常见说法 |

更多定制点：`normalize`（抽取结果换算，`Slots.CLEAR` 表示清空某个槽位）、`describe`（确认文案里的描述）、
`completedText`（收齐时说的话）。完整例子见 demo 的 `CollectTargetNode`。

任务有多个收集阶段时，中间阶段也写成 `SlotFillingNode`，**不声明 `routeSpec`**（只能由上一阶段推进过来），
上一阶段的 `nextNode()` 指向它。

## 5. 任务最后一步：确认后提交（ConfirmNode）

```java
@Component
public class RepairSubmitNode extends ConfirmNode {

    private final RepairCollectNode collect;
    private final RepairTicketMapper tickets;   // 自己的 Mapper

    public RepairSubmitNode(NodeSupport support, RepairCollectNode collect, RepairTicketMapper tickets) {
        super(support);
        this.collect = collect;
        this.tickets = tickets;
    }

    @Override public String id() { return "repair_submit"; }
    @Override protected String actionName() { return "提交报修"; }

    /** 要用户确认的内容，固定文案，不经过模型。这里直接用收集节点的描述："问题类型：水管漏水、地址：…" */
    @Override protected String summary(TaskFrame task) {
        return collect.describe(task.getSlots());
    }

    /** 写操作。idempotencyKey 同一个任务不变，必须用它去重 */
    @Override protected CommitResult commit(TaskFrame task, String idempotencyKey) {
        String ticketNo = tickets.insertIfAbsent(idempotencyKey, task.getSlots());   // 唯一索引去重
        return CommitResult.ok("已提交，工单号 " + ticketNo + "，维修师傅会尽快联系你。");
    }

    /** 用户要修改时回到哪个阶段 */
    @Override protected String editNode(TaskFrame task) { return "repair_collect"; }
}
```

框架负责的部分：

- 展示确认内容，在任务帧上记下"正在等用户确认"（路由提示词里能看到）
- 用户说"确认""好的"直接提交，不调用模型；说"电话改成 139…"会回到 `editNode` 修改，收齐后自动回来重新确认；
  说"有个地方不对"会问改哪一项；插话回来只说"继续"会重新展示确认内容，不会误提交
- 提交失败（`CommitResult.failed` 或抛异常）时保持待确认，用户可以直接重试

**为什么必须幂等**：commit 成功后、本轮结束前如果出错，会话状态会回滚到"待确认"，用户再说一次"确认"会再调用一次 commit。

## 6. 自己的表和 Mapper

- 建表脚本放在自己工程的 `resources/db/` 下，追加到 `spring.sql.init.schema-locations`
- Mapper 接口加 `@Mapper`、放在启动类所在的包下面，MyBatis 会自动扫描（框架没有用 `@MapperScan`，不会影响业务工程的扫描）
- 注意：框架打开了 MyBatis 的 `callSettersOnNulls`（结果映射到 Map 时保留值为 null 的列），这是全局设置

## 7. 工具节点（可选）

需要查业务数据时，参考 demo 的 `BizQueryNode`：每个工具实现 `FlowTool`，节点里调用 `streamWithTools(...)`。
模型缺参数时会通过内置的 `ask_user` 工具追问，用户的回答会被路由回这个节点。

## 8. 测试

参考 `smart-flow-demo` 的 `WorkflowScenarioTest`：模型换成按脚本返回的 stub、存储换成内存实现，
不需要数据库和真实模型就能把"开始 → 补充 → 插话 → 修改 → 确认 / 取消"整条链路跑一遍。
槽位的校验逻辑用 `applyExtraction` 直接测（参考 `CollectTargetNodeTest`）。

## 检查清单

- [ ] 测试页「节点 / 提示词」页签里，新节点都挂在 router 下且打了 ✓；任务中间阶段出现在"未挂载"里是正常的
- [ ] 每个任务入口的 `routeSpec` 描述写清了边界，示例 2～5 条
- [ ] 每个任务最终都会走到 `nextNode() == null` 的阶段或者 `ConfirmNode`，否则任务永远不会结束
- [ ] `commit` 用了幂等键
- [ ] 提示词里没有写死助手名字

# Langfuse tracing

Langfuse 是可选的外部观测服务，默认关闭。本地 PostgreSQL 执行记录和调试页面继续工作。
接入使用 OpenTelemetry Java **1.67.0**，通过官方支持的 OTLP/HTTP protobuf 端点发送数据，
不使用已弃用的 ingestion API，也不接管应用的全局 OpenTelemetry provider。

## 启用

在启动应用的同一个 shell 中加载配置。密钥在 Langfuse 项目的 **Settings → API Keys** 中创建。
`LANGFUSE_BASE_URL` 使用该项目所在区域或自托管部署的根 URL，不加 `/api/public/otel`。

```bash
# 已在 ~/.zshrc 配置密钥时：
source ~/.zshrc
export LANGFUSE_ENABLED=true
export LANGFUSE_TRACING_ENVIRONMENT=development
# 可选：版本号、Git commit 或镜像版本，方便对比发布前后的效果
export LANGFUSE_RELEASE=local-development

mvn install -DskipTests
mvn -pl smart-flow-demo spring-boot:run
```

也可复制根目录的 `.env.example` 为 `.env` 并填写配置。在启动前执行
`set -a; source .env; set +a`；Spring Boot 不会自动加载 `.env`。
`.env` 已被 Git 忽略。IDE 启动时，需在该运行配置中设置环境变量并重启。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `LANGFUSE_ENABLED` | `false` | 显式开启外发 |
| `LANGFUSE_BASE_URL` | 空 | 例如 EU `https://cloud.langfuse.com`、US `https://us.cloud.langfuse.com`，或自托管根 URL |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | 空 | 项目 API key pair |
| `LANGFUSE_CAPTURE_CONTENT` | `false` | 开启后发送经脱敏的提示词、工具参数/结果和回复 |
| `LANGFUSE_TRACING_ENVIRONMENT` | `development` | 区分开发、预发和生产 |
| `LANGFUSE_RELEASE` | 空 | 应用发布版本 |
| `LANGFUSE_SAMPLE_RATE` | `1.0` | 按完整轮次采样，范围 0–1 |

上述环境变量由 demo 的 `application.yml` 映射到 `flow.langfuse.*`。
新业务工程依赖 core 时，可以复制这一段配置，或直接使用 Spring Boot 标准环境变量
`FLOW_LANGFUSE_ENABLED`、`FLOW_LANGFUSE_BASE_URL` 等。

## Trace 的结构

每个用户轮次对应一个 trace，同一对话的轮次通过 session 分组：

```text
handle-chat-turn                      chain，用户输入 → 最终回复
  execute-router                      chain
    generate-route                    generation
  execute-biz_query                   agent
    generate-answer                   generation，第 1 次模型请求
    execute-get_weather               tool
    generate-answer                   generation，第 2 次模型请求
  generate-extract_memory             generation，异步任务，可晚于根节点结束
```

模型每次真实请求分别记录模型名、请求参数、输入/输出 token、完成原因和耗时；
工具与请求它的 generation 为兄弟节点，工具 metadata 的 `requesting_call_id` 可与模型调用对照。
`answer#2` 等轮次编号保留在 metadata 的 `purpose`，观察名称保持稳定，便于聚合和评测。
开启正文采集时，generation 中包含模型实际看到的消息历史、工具定义及 JSON Schema，
工具调用/结果使用匹配的调用 ID；响应中存在的 thinking 会作为 `reasoning_content` 记录。

所有观察都带上环境、release、tags、session/user 和本地 `turn_id`。
session/user 标识用项目 secret key 做 HMAC-SHA256，保留跨轮次关联而不直接外发原始标识。
更换 secret key 后，这两个外部标识会变化。
显式关联执行 ID 使流式回调和后台记忆线程保持正确父子关系，无需传播线程本地变量。
结束轮次的轻量上下文最多保留一小时、10,000 项，以供后台任务关联；超过保留期的任务会形成独立 trace。

## 内容和可靠性

默认发送模型、用量、耗时、状态、关联标识和层级，正文显示 `Content capture disabled`。
这适合本应用包含个人信息查询的场景。需要查看提示词和工具往返时：

```bash
export LANGFUSE_CAPTURE_CONTENT=true
```

开启后，内容在外发前统一脱敏：结构化姓名/年龄/电话/邮箱/认证字段，以及正文中的常见手机号、邮箱和 token。
嵌套在字符串里的 JSON 工具参数/结果也会处理。通用规则无法可靠识别自然语言中的所有姓名、地址和业务敏感信息；
生产业务应提供 `LangfuseContentMasker` Bean 实现自己的策略，并在开启正文采集前审查样例。
完整运行状态不会作为根 trace 输入；根输入/输出仅为用户消息与最终回复。
单个内容字段默认限制为 32,000 字符，可通过 `flow.langfuse.max-content-chars` 调整。

exporter 使用有界异步队列（2,048 spans）、每批最多 256 spans、2 秒刷新周期及 5 秒请求超时。
网络错误不会中断用户对话；队列满时可丢弃遥测。应用正常退出时等待刷新，硬杀进程仍可能丢失未发送数据。
未完成的观察缓存有大小和过期限制，避免异常路径导致无限占用内存。
只记录有用的 AI 操作，不向 Langfuse 发送 HTTP/SQL 框架噪声。

## 验证

```bash
# 本地验证：真实应用的工具循环、流式模型回调、后台记忆、脱敏、采样、OTLP HTTP 和认证 header。
# 使用本地模拟模型，不需要数据库、真实模型 Key 或 Langfuse 账号。
mvn -pl smart-flow-core -am test -Dtest=LangfuseTracingTest -Dsurefire.failIfNoSpecifiedTests=false

# 显式向已配置的 Langfuse 项目发送合成测试数据，打印 trace ID，不打印密钥。
source ~/.zshrc
LANGFUSE_VERIFY_LIVE=true mvn -pl smart-flow-core -am test \
  '-Dtest=LangfuseTracingTest#verifyLiveLangfuseWithSyntheticApplicationToolLoop' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

在 Langfuse Observations/Traces 中按名称 `handle-chat-turn` 或 metadata 的 `turn_id` 查找，
检查两次 `generate-answer`、中间的天气工具及后台记忆观察。
Sessions 可查看同一对话的多轮结果；模型用量可用于 Langfuse 已识别模型的成本计算，未知或自定义模型需配置定价。
实时 ingestion 使用 `x-langfuse-ingestion-version: 4` header。

自动化核对使用当前 Observations API，避免旧 Traces API：

```bash
export LANGFUSE_HOST="$LANGFUSE_BASE_URL"
npx --yes langfuse-cli api observations list --trace-id '<trace-id>' \
  --fields core,basic,io,model,usage,metadata,trace_context --json
```

参考：[Langfuse 官方 Java/OTel 接入](https://langfuse.com/integrations/native/opentelemetry)、
[Trace 最佳实践](https://langfuse.com/docs/observability/best-practices)、
[已安装的 Agent Skill 来源](https://github.com/langfuse/skills/tree/main/skills/langfuse)。

-- =====================================================================
-- 智慧民生工作流 - 最小闭环表结构（PostgreSQL）
-- 约定：不建外键，关联关系由应用层维护；ID 统一用应用生成的 UUID 字符串
-- 所有语句可重复执行
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 会话：保存跨轮次的结构化状态（任务栈、槽位、摘要）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_session (
    session_id      VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(64),
    state           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    turn_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_flow_session_user ON flow_session (user_id, updated_at DESC);

COMMENT ON TABLE  flow_session        IS '会话';
COMMENT ON COLUMN flow_session.state  IS '会话结构化状态：taskStack（任务栈，含当前节点与槽位）、summary（早期对话摘要）';

-- ---------------------------------------------------------------------
-- 2. 消息：对话原文，短期记忆从这里取最近 N 条
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_message (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL,
    turn_id         VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,          -- user / assistant
    content         TEXT         NOT NULL,
    node_id         VARCHAR(64),                    -- assistant 消息由哪个节点产生
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_flow_message_session ON flow_message (session_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_flow_message_turn    ON flow_message (turn_id);

COMMENT ON TABLE flow_message IS '对话消息';

-- ---------------------------------------------------------------------
-- 3. 轮次：一次用户输入 = 一轮，记录路由结果、节点路径、执行前后状态
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_turn (
    turn_id         VARCHAR(64)  PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL,
    seq             INT          NOT NULL,          -- 会话内第几轮，从 1 开始
    user_input      TEXT         NOT NULL,
    final_reply     TEXT,
    route_action    VARCHAR(32),                    -- CONTINUE / START_TASK / DIGRESS / CANCEL / CHAT
    route_target    VARCHAR(64),
    node_path       JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- ["router","collect_target"]
    state_before    JSONB,
    state_after     JSONB,
    status          VARCHAR(16)  NOT NULL,          -- RUNNING / SUCCESS / ERROR
    error_msg       TEXT,
    latency_ms      BIGINT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_flow_turn_session ON flow_turn (session_id, seq DESC);
CREATE INDEX IF NOT EXISTS idx_flow_turn_started ON flow_turn (started_at DESC);

COMMENT ON TABLE  flow_turn           IS '对话轮次';
COMMENT ON COLUMN flow_turn.node_path IS '本轮实际经过的节点，按执行顺序';

-- ---------------------------------------------------------------------
-- 4. 节点执行：每个节点每次执行一条，节点流转记录来自这里
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_node_exec (
    exec_id         VARCHAR(64)  PRIMARY KEY,
    turn_id         VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    seq             INT          NOT NULL,          -- 本轮内第几个执行的节点
    node_id         VARCHAR(64)  NOT NULL,
    node_type       VARCHAR(32)  NOT NULL,          -- ROUTER / SLOT_FILLING / CHAT / TOOL_AGENT / CONFIRM
    input_state     JSONB,                          -- 节点收到的状态（已脱去大字段）
    output_update   JSONB,                          -- 节点返回的状态更新
    status          VARCHAR(16)  NOT NULL,          -- RUNNING / SUCCESS / ERROR
    error_msg       TEXT,
    latency_ms      BIGINT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_flow_node_exec_turn    ON flow_node_exec (turn_id, seq);
CREATE INDEX IF NOT EXISTS idx_flow_node_exec_node    ON flow_node_exec (node_id, started_at DESC);

COMMENT ON TABLE flow_node_exec IS '节点执行记录';

-- ---------------------------------------------------------------------
-- 5. 模型调用：真实发给模型的完整请求（合并默认参数之后）与完整响应
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_llm_call (
    call_id         VARCHAR(64)  PRIMARY KEY,
    turn_id         VARCHAR(64),
    session_id      VARCHAR(64),
    exec_id         VARCHAR(64),                    -- 关联 flow_node_exec
    node_id         VARCHAR(64),
    purpose         VARCHAR(64),                    -- 节点内用途：route / extract_slots / reply ...
    provider        VARCHAR(32),
    model_name      VARCHAR(128),
    streaming       BOOLEAN      NOT NULL DEFAULT FALSE,
    request_body    JSONB        NOT NULL,          -- messages + parameters
    response_body   JSONB,                          -- 完整响应（含 tool calls、finish reason、usage）
    response_text   TEXT,
    input_tokens    INT,
    output_tokens   INT,
    total_tokens    INT,
    finish_reason   VARCHAR(32),
    status          VARCHAR(16)  NOT NULL,          -- RUNNING / SUCCESS / ERROR
    error_msg       TEXT,
    latency_ms      BIGINT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_flow_llm_call_turn  ON flow_llm_call (turn_id, started_at);
CREATE INDEX IF NOT EXISTS idx_flow_llm_call_exec  ON flow_llm_call (exec_id);
CREATE INDEX IF NOT EXISTS idx_flow_llm_call_model ON flow_llm_call (model_name, started_at DESC);

COMMENT ON TABLE flow_llm_call IS '模型调用原始报文';

-- ---------------------------------------------------------------------
-- 6. 工具调用：最小闭环里还没有工具节点，表先建好，B2 / C2 接入时使用
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_tool_call (
    tool_call_id    VARCHAR(64)  PRIMARY KEY,
    turn_id         VARCHAR(64),
    session_id      VARCHAR(64),
    exec_id         VARCHAR(64),
    llm_call_id     VARCHAR(64),                    -- 由哪次模型调用发起
    node_id         VARCHAR(64),
    tool_name       VARCHAR(128) NOT NULL,
    arguments       JSONB,
    result          TEXT,
    status          VARCHAR(16)  NOT NULL,
    error_msg       TEXT,
    latency_ms      BIGINT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_flow_tool_call_turn ON flow_tool_call (turn_id, started_at);
CREATE INDEX IF NOT EXISTS idx_flow_tool_call_exec ON flow_tool_call (exec_id);

COMMENT ON TABLE flow_tool_call IS '工具调用记录';

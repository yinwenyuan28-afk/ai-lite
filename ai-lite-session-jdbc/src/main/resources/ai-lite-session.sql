-- AI Lite 会话表。按实际数据库调整 VARCHAR/CLOB 类型；
-- 表名必须与 JdbcAgentSessionStore 构造参数一致。
--
-- 派生列说明（由 AgentSessionLoop.finish 维护）：
--   TITLE        会话标题，取首条用户消息截断形式，供列表展示；可为 NULL
--   MESSAGE_COUNT 消息条目数（不含压缩/用量条目）
--   TOTAL_TOKENS  累计 token 数，只统计成功轮次
--   COMPACTION_LEASE_UNTIL 压缩租约到期时间（毫秒时间戳）；NULL 表示无租约。
--                 多节点同时判定超限时，只有抢到租约的实例执行摘要，避免双付费。
--                 租约只是省钱手段，不是正确性依赖（追加语义下双压也只是多一条摘要）。
--
-- 注意：0.1.x 的历史表结构无这些列。本项目尚未正式使用，
-- 因此这里只提供完整建表语句，不提供 ALTER 迁移段。
-- 索引 (USER_ID, UPDATED_AT) 支撑 listByUser 的分页查询。
CREATE TABLE AI_LITE_SESSION (
    USER_ID       VARCHAR(128) NOT NULL,
    SESSION_ID    VARCHAR(191) NOT NULL,
    BIZ_ID        VARCHAR(128),
    VERSION       BIGINT       NOT NULL,
    MESSAGES_JSON CLOB,
    TITLE         VARCHAR(255),
    MESSAGE_COUNT INT          DEFAULT 0,
    TOTAL_TOKENS  BIGINT       DEFAULT 0,
    COMPACTION_LEASE_UNTIL BIGINT,
    UPDATED_AT    TIMESTAMP,
    PRIMARY KEY (USER_ID, SESSION_ID)
);

CREATE INDEX IDX_AI_LITE_SESSION_USER_UPDATED
    ON AI_LITE_SESSION (USER_ID, UPDATED_AT);

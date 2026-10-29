-- AI Lite 运行时目录。只有主键，不建额外唯一索引。
-- API_KEY 列是供应商密钥，接口只返回脱敏值，不要写进日志。
-- 按实际数据库调整 VARCHAR / TIMESTAMP。
--
-- 四张表的表名都可以改，改完同步修改对应的配置键：
--   AI_LITE_PROVIDER_OVERLAY → ai-lite.catalog.provider-table
--   AI_LITE_MODEL            → ai-lite.catalog.model-table
--   AI_LITE_BIZ_BINDING      → ai-lite.catalog.biz-table
--   AI_LITE_ADMIN_SETTING    → ai-lite.catalog.setting-table
-- 表名只允许字母、数字和下划线；配错会在装配期或首次读写时明确报错。

CREATE TABLE AI_LITE_PROVIDER_OVERLAY (
    PROVIDER_ID        VARCHAR(64)  NOT NULL,
    BASE_URL           VARCHAR(500),
    API_KEY            VARCHAR(500),
    CONNECT_TIMEOUT_MS INT,
    READ_TIMEOUT_MS    INT,
    UPDATE_TIME        TIMESTAMP,
    PRIMARY KEY (PROVIDER_ID)
);

CREATE TABLE AI_LITE_MODEL (
    MODEL_ROW_ID    VARCHAR(64)  NOT NULL,
    PROVIDER_ID     VARCHAR(64)  NOT NULL,
    MODEL_ID        VARCHAR(128) NOT NULL,
    READY           INT          NOT NULL,
    REASONING       INT,
    INPUT_MODES     VARCHAR(64),
    CONTEXT_WINDOW  INT,
    MAX_TOKENS      INT,
    THINKING_FORMAT VARCHAR(64),
    DISPLAY_NAME    VARCHAR(128),
    UPDATE_TIME     TIMESTAMP,
    PRIMARY KEY (MODEL_ROW_ID)
);

CREATE TABLE AI_LITE_BIZ_BINDING (
    BIZ_ID          VARCHAR(64)  NOT NULL,
    PROVIDER_ID     VARCHAR(64)  NOT NULL,
    MODEL_ID        VARCHAR(128) NOT NULL,
    THINKING_LEVEL  VARCHAR(32)  NOT NULL,
    UPDATE_TIME     TIMESTAMP,
    PRIMARY KEY (BIZ_ID)
);

-- 管理端运行时开关。目前只有 call-body：none / failure / all。
CREATE TABLE AI_LITE_ADMIN_SETTING (
    SETTING_KEY   VARCHAR(64) NOT NULL,
    SETTING_VALUE VARCHAR(32),
    UPDATE_TIME   TIMESTAMP,
    PRIMARY KEY (SETTING_KEY)
);

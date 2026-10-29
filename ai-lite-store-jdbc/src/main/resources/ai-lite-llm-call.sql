-- AI Lite 调用记录表。只存摘要，不含提示词、响应体和密钥。
-- 表名可改，对应配置键：
--   ai-lite.store.jdbc.table-name      -> AI_LITE_LLM_CALL
--   ai-lite.store.jdbc.body-table-name -> AI_LITE_LLM_CALL_BODY
-- body-table-name 不配时按 table-name + "_BODY" 推导：只把 table-name 改成
-- MY_CALL 时，正文表要一并建成 MY_CALL_BODY。
CREATE TABLE AI_LITE_LLM_CALL (
    CALL_ID        VARCHAR(64)  NOT NULL,
    BIZ_ID         VARCHAR(128) NOT NULL,
    PROVIDER       VARCHAR(64),
    MODEL_ID       VARCHAR(128),
    API            VARCHAR(64),
    PROFILE        VARCHAR(64),
    THINKING_LEVEL VARCHAR(32),
    ROUND_NO       INT,
    INPUT_TOKENS   INT,
    OUTPUT_TOKENS  INT,
    TOTAL_TOKENS   INT,
    LATENCY_MS     BIGINT,
    STOP_REASON    VARCHAR(32),
    SUCCESS        INT,
    ERROR_BRIEF    VARCHAR(255),
    CREATE_TIME    TIMESTAMP,
    PRIMARY KEY (CALL_ID)
);

-- 只有开启输入输出记录时才写入。INPUT_TEXT / OUTPUT_TEXT 不含密钥和图片原文。
CREATE TABLE AI_LITE_LLM_CALL_BODY (
    CALL_ID     VARCHAR(64) NOT NULL,
    INPUT_TEXT  CLOB,
    OUTPUT_TEXT CLOB,
    PRIMARY KEY (CALL_ID)
);

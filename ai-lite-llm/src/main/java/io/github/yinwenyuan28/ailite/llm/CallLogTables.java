package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用记录相关表名的推导规则。
 *
 * <p>调用记录跨两张表：摘要表（{@code LlmCallStore} 写入的行）和正文表
 * （只在开启输入输出记录时写入）。正文表在历史上是硬编码的
 * {@code AI_LITE_LLM_CALL_BODY}，而摘要表可以通过
 * {@code ai-lite.store.jdbc.table-name} 改名，于是改名用户会进入一种半改名状态：
 * 摘要写进 {@code MY_CALL}，正文仍去写 {@code AI_LITE_LLM_CALL_BODY}。
 * 这条 INSERT 失败后异常被异步日志吞掉，界面上表现为"调用记录有了，正文永远为空"。</p>
 *
 * <p>本类把"正文表名跟随摘要表名"这条规则收在一处，写侧（{@code ai-lite-store-jdbc}）
 * 与读侧（{@code ai-lite-admin-api} 的调用记录查询）都调它，避免两边各写一份
 * {@code + "_BODY"} 后悄悄跑偏。</p>
 */
public final class CallLogTables {

    /** 摘要表默认名，与 {@code ai-lite-llm-call.sql} 一致。 */
    public static final String DEFAULT_TABLE = "AI_LITE_LLM_CALL";

    private CallLogTables() {
    }

    /**
     * 由摘要表名推导正文表名。
     *
     * <p>默认表名 {@value #DEFAULT_TABLE} 推导结果正好是
     * {@code AI_LITE_LLM_CALL_BODY}，与旧版本的硬编码表名一致，因此不配置的
     * 老用户行为不变。</p>
     *
     * @param callTable 摘要表名
     * @return 正文表名；入参为空时返回 null
     */
    public static String bodyTable(String callTable) {
        if (callTable == null || callTable.trim().isEmpty()) {
            return null;
        }
        return callTable.trim() + "_BODY";
    }

    /**
     * 解析实际使用的正文表名：显式配置优先，否则跟随摘要表推导。
     *
     * @param callTable     摘要表名，可为空
     * @param configuredBody 显式配置的正文表名，可为空
     * @return 正文表名；两者都空时返回 null
     */
    public static String resolveBodyTable(String callTable, String configuredBody) {
        if (configuredBody != null && !configuredBody.trim().isEmpty()) {
            return configuredBody.trim();
        }
        return bodyTable(callTable);
    }
}

package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调用正文表名可自定义，且默认跟随摘要表名。
 *
 * <p>回归重点：正文表名曾经硬编码为 {@code AI_LITE_LLM_CALL_BODY}，而摘要表名
 * 通过 {@code ai-lite.store.jdbc.table-name} 可改。改名用户会进入半改名状态——
 * 摘要写进自定义表，正文仍去写默认表；那条 INSERT 失败后异常被
 * {@code AsyncLlmCallLogger} 吞掉，表现为"调用了、摘要有了、正文永远空"。</p>
 */
public class CallBodyTableNameTest {

    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:body-table-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 只配摘要表名时，正文表自动跟随为 {@code <主表>_BODY}。
     */
    @Test
    public void bodyTableFollowsCallTableByDefault() {
        createTables(jdbc, "MY_CALL", "MY_CALL_BODY");

        JdbcLlmCallStore store = new JdbcLlmCallStore(jdbc, "MY_CALL");
        store.insert(recordWithBody());

        assertThat(count("MY_CALL")).isEqualTo(1);
        assertThat(count("MY_CALL_BODY")).isEqualTo(1);
        // 没有悄悄写进默认表。
        assertThat(tableExists("AI_LITE_LLM_CALL_BODY")).isFalse();
    }

    /**
     * 显式配置正文表名时以配置为准。
     */
    @Test
    public void explicitBodyTableWins() {
        createTables(jdbc, "MY_CALL", "MY_CALL_DETAIL");

        JdbcLlmCallStore store = new JdbcLlmCallStore(jdbc, "MY_CALL", "MY_CALL_DETAIL");
        store.insert(recordWithBody());

        assertThat(store.getBodyTable()).isEqualTo("MY_CALL_DETAIL");
        assertThat(count("MY_CALL_DETAIL")).isEqualTo(1);
    }

    /**
     * 默认表名推导结果必须还是旧表名，升级不改行为的底线。
     */
    @Test
    public void defaultDerivationKeepsLegacyBodyTable() {
        JdbcLlmCallStore store = new JdbcLlmCallStore(jdbc, "AI_LITE_LLM_CALL");
        assertThat(store.getBodyTable()).isEqualTo("AI_LITE_LLM_CALL_BODY");

        JdbcLlmCallStore legacy = new JdbcLlmCallStore(jdbc);
        assertThat(legacy.getTable()).isEqualTo("AI_LITE_LLM_CALL");
        assertThat(legacy.getBodyTable()).isEqualTo("AI_LITE_LLM_CALL_BODY");
    }

    /**
     * 正文表名同样走白名单校验。
     */
    @Test
    public void illegalBodyTableIsRejected() {
        assertThatThrownBy(() -> new JdbcLlmCallStore(jdbc, "MY_CALL", "MY_CALL_BODY; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");
    }

    /**
     * 正文表不存在时摘要仍然落库：这是既有语义（两条独立语句），
     * 本测试把它固化成契约，避免以后有人"顺手"改成一个事务。
     */
    @Test
    public void missingBodyTableStillWritesSummary() {
        createTables(jdbc, "MY_CALL");

        JdbcLlmCallStore store = new JdbcLlmCallStore(jdbc, "MY_CALL");
        assertThatThrownBy(() -> store.insert(recordWithBody()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // 摘要已经写进去了——这正是"看起来正常、正文永远空"的成因。
        assertThat(count("MY_CALL")).isEqualTo(1);
    }

    private static LlmCallRecord recordWithBody() {
        LlmCallRecord row = new LlmCallRecord();
        row.setCallId("c1");
        row.setBizId("biz");
        row.setProvider("p");
        row.setModelId("m");
        row.setSuccess(Integer.valueOf(1));
        row.setCreateTime(new Date());
        row.setInputBody("问");
        row.setOutputBody("答");
        return row;
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private boolean tableExists(String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 建摘要表；给了 {@code bodyTable} 时再建正文表。
     *
     * @param jdbc      JDBC
     * @param callTable 摘要表名
     * @param bodyTable 正文表名，可变参数以便只建摘要表
     */
    private static void createTables(JdbcTemplate jdbc, String callTable, String... bodyTable) {
        jdbc.execute("CREATE TABLE " + callTable + " ("
                + "CALL_ID VARCHAR(64) PRIMARY KEY, BIZ_ID VARCHAR(128) NOT NULL,"
                + "PROVIDER VARCHAR(64), MODEL_ID VARCHAR(128), API VARCHAR(64), PROFILE VARCHAR(64),"
                + "THINKING_LEVEL VARCHAR(32), ROUND_NO INT, INPUT_TOKENS INT, OUTPUT_TOKENS INT,"
                + "TOTAL_TOKENS INT, LATENCY_MS BIGINT, STOP_REASON VARCHAR(32), SUCCESS INT,"
                + "ERROR_BRIEF VARCHAR(255), CREATE_TIME TIMESTAMP)");
        for (String body : bodyTable) {
            jdbc.execute("CREATE TABLE " + body + " ("
                    + "CALL_ID VARCHAR(64) PRIMARY KEY, INPUT_TEXT CLOB, OUTPUT_TEXT CLOB)");
        }
    }
}

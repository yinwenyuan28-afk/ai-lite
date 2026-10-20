package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC 调用记录：字段写入数据库。
 */
public class JdbcLlmCallStoreTest {

    private JdbcTemplate jdbc;
    private JdbcLlmCallStore store;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:call_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE AI_LITE_LLM_CALL ("
                + "CALL_ID VARCHAR(64) PRIMARY KEY, BIZ_ID VARCHAR(128) NOT NULL,"
                + "PROVIDER VARCHAR(64), MODEL_ID VARCHAR(128), API VARCHAR(64), PROFILE VARCHAR(64),"
                + "THINKING_LEVEL VARCHAR(32), ROUND_NO INT, INPUT_TOKENS INT, OUTPUT_TOKENS INT,"
                + "TOTAL_TOKENS INT, LATENCY_MS BIGINT, STOP_REASON VARCHAR(32), SUCCESS INT,"
                + "ERROR_BRIEF VARCHAR(255), CREATE_TIME TIMESTAMP)");
        store = new JdbcLlmCallStore(jdbc);
    }

    @Test
    public void insertWritesAllColumns() {
        LlmCallRecord record = new LlmCallRecord();
        record.setCallId("c1");
        record.setBizId("biz");
        record.setProvider("test");
        record.setModelId("m");
        record.setApi("openai-completions");
        record.setProfile("main");
        record.setThinkingLevel("off");
        record.setRoundNo(Integer.valueOf(1));
        record.setInputTokens(Integer.valueOf(3));
        record.setOutputTokens(Integer.valueOf(2));
        record.setTotalTokens(Integer.valueOf(5));
        record.setLatencyMs(Long.valueOf(12L));
        record.setStopReason("stop");
        record.setSuccess(Integer.valueOf(1));
        record.setErrorBrief(null);
        record.setCreateTime(new Date());

        store.insert(record);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM AI_LITE_LLM_CALL WHERE CALL_ID = 'c1'");
        assertThat(row.get("BIZ_ID")).isEqualTo("biz");
        assertThat(row.get("TOTAL_TOKENS")).isEqualTo(Integer.valueOf(5));
        assertThat(row.get("SUCCESS")).isEqualTo(Integer.valueOf(1));
    }
}

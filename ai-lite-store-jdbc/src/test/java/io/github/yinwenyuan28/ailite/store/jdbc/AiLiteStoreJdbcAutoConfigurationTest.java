package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC 调用记录自动装配：表名必填、开关、条件缺失与用户覆盖。
 */
public class AiLiteStoreJdbcAutoConfigurationTest {

    private static JdbcDataSource dataSource;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLiteStoreJdbcAutoConfiguration.class))
            .withUserConfiguration(JdbcConfig.class);

    @Before
    public void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:store-auto-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTable(jdbc, "AI_LITE_LLM_CALL");
        createTable(jdbc, "CUSTOM_CALL");
    }

    @Test
    public void createsJdbcStoreWhenTableNameConfigured() {
        runner.withPropertyValues("ai-lite.store.jdbc.table-name=AI_LITE_LLM_CALL").run(context -> {
            assertThat(context).hasSingleBean(LlmCallStore.class);
            LlmCallStore store = context.getBean(LlmCallStore.class);
            assertThat(store).isInstanceOf(JdbcLlmCallStore.class);

            store.insert(record());
            assertThat(count("AI_LITE_LLM_CALL")).isEqualTo(1);
        });
    }

    @Test
    public void customTableNameIsUsed() {
        runner.withPropertyValues("ai-lite.store.jdbc.table-name=CUSTOM_CALL").run(context -> {
            LlmCallStore store = context.getBean(LlmCallStore.class);
            store.insert(record());

            assertThat(count("CUSTOM_CALL")).isEqualTo(1);
            assertThat(count("AI_LITE_LLM_CALL")).isZero();
        });
    }

    @Test
    public void missingTableNameFailsFast() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("ai-lite.store.jdbc.table-name 必须显式配置")
                    .hasStackTraceContaining("ai-lite-llm-call.sql");
        });
    }

    @Test
    public void blankTableNameFailsFast() {
        runner.withPropertyValues("ai-lite.store.jdbc.table-name=   ").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("ai-lite.store.jdbc.table-name 必须显式配置");
        });
    }

    @Test
    public void storeSwitchDisablesAutoConfiguration() {
        runner.withPropertyValues(
                "ai-lite.store.jdbc.table-name=AI_LITE_LLM_CALL",
                "ai-lite.store.jdbc.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LlmCallStore.class);
        });
    }

    @Test
    public void globalSwitchDisablesAutoConfiguration() {
        runner.withPropertyValues(
                "ai-lite.enabled=false",
                "ai-lite.store.jdbc.table-name=AI_LITE_LLM_CALL").run(context -> {
            assertThat(context).doesNotHaveBean(LlmCallStore.class);
        });
    }

    @Test
    public void userStoreOverridesJdbc() {
        runner.withUserConfiguration(UserStoreConfig.class)
                .withPropertyValues("ai-lite.store.jdbc.table-name=AI_LITE_LLM_CALL")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmCallStore.class);
                    assertThat(context.getBean(LlmCallStore.class))
                            .isInstanceOf(NoopLlmCallStore.class);
                });
    }

    @Test
    public void userStoreSuppressesMissingTableNameFailure() {
        // 用户自己提供了 store 时，本配置整体让位，不该因为没配表名而启动失败。
        runner.withUserConfiguration(UserStoreConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LlmCallStore.class);
        });
    }

    @Test
    public void withoutJdbcTemplateAutoConfigurationIsSkipped() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiLiteStoreJdbcAutoConfiguration.class))
                .withPropertyValues("ai-lite.store.jdbc.table-name=AI_LITE_LLM_CALL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LlmCallStore.class);
                });
    }

    private static LlmCallRecord record() {
        LlmCallRecord row = new LlmCallRecord();
        row.setCallId("call-1");
        row.setBizId("biz");
        row.setProvider("p");
        row.setModelId("m");
        row.setApi("openai-completions");
        row.setRoundNo(Integer.valueOf(1));
        row.setInputTokens(Integer.valueOf(10));
        row.setOutputTokens(Integer.valueOf(5));
        row.setTotalTokens(Integer.valueOf(15));
        row.setLatencyMs(Long.valueOf(20L));
        row.setStopReason("stop");
        row.setSuccess(Integer.valueOf(1));
        row.setCreateTime(new Date());
        return row;
    }

    private static int count(String table) {
        Integer value = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(1) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private static void createTable(JdbcTemplate jdbc, String table) {
        jdbc.execute("CREATE TABLE " + table + " ("
                + "CALL_ID VARCHAR(64) NOT NULL, BIZ_ID VARCHAR(128) NOT NULL,"
                + "PROVIDER VARCHAR(64), MODEL_ID VARCHAR(128), API VARCHAR(64),"
                + "PROFILE VARCHAR(64), THINKING_LEVEL VARCHAR(32), ROUND_NO INT,"
                + "INPUT_TOKENS INT, OUTPUT_TOKENS INT, TOTAL_TOKENS INT, LATENCY_MS BIGINT,"
                + "STOP_REASON VARCHAR(32), SUCCESS INT, ERROR_BRIEF VARCHAR(255),"
                + "CREATE_TIME TIMESTAMP, PRIMARY KEY (CALL_ID))");
    }

    /**
     * 提供 JdbcTemplate。
     */
    @Configuration
    static class JdbcConfig {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(dataSource);
        }
    }

    /**
     * 用户自定义存储。
     */
    @Configuration
    static class UserStoreConfig {
        @Bean
        public LlmCallStore customStore() {
            return new NoopLlmCallStore();
        }
    }
}

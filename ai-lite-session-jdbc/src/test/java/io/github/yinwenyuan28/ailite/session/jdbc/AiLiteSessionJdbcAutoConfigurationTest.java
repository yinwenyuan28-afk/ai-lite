package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.InMemoryAgentSessionStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC 会话自动装配：默认表名、配置表名、开关和用户覆盖。
 */
public class AiLiteSessionJdbcAutoConfigurationTest {

    private static JdbcDataSource dataSource;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLiteSessionJdbcAutoConfiguration.class))
            .withUserConfiguration(JdbcConfig.class);

    @Before
    public void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:session-auto-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTable(jdbc, "AI_LITE_SESSION");
        createTable(jdbc, "CUSTOM_SESSION");
    }

    @Test
    public void createsJdbcStoreWithDefaultTable() {
        runner.run(context -> {
            AgentSessionStore store = context.getBean(AgentSessionStore.class);
            assertThat(store).isInstanceOf(JdbcAgentSessionStore.class);

            store.save(session());
            assertThat(store.find("u", "s")).isPresent();
            assertThat(count("AI_LITE_SESSION")).isEqualTo(1);
        });
    }

    @Test
    public void configuredTableNameIsUsed() {
        runner.withPropertyValues("ai-lite.session.jdbc.table-name=CUSTOM_SESSION").run(context -> {
            AgentSessionStore store = context.getBean(AgentSessionStore.class);
            store.save(session());

            assertThat(count("CUSTOM_SESSION")).isEqualTo(1);
            assertThat(count("AI_LITE_SESSION")).isZero();
        });
    }

    @Test
    public void globalSwitchDisablesStore() {
        runner.withPropertyValues("ai-lite.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AgentSessionStore.class);
        });
    }

    @Test
    public void sessionSwitchDisablesStore() {
        runner.withPropertyValues("ai-lite.session.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AgentSessionStore.class);
        });
    }

    @Test
    public void userStoreOverridesJdbc() {
        runner.withUserConfiguration(UserStoreConfig.class).run(context -> {
            assertThat(context).hasSingleBean(AgentSessionStore.class);
            assertThat(context.getBean(AgentSessionStore.class))
                    .isInstanceOf(InMemoryAgentSessionStore.class);
        });
    }

    @Test
    public void invalidTableNameFailsFast() {
        runner.withPropertyValues("ai-lite.session.jdbc.table-name=BAD;DROP TABLE X").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("非法表名");
        });
    }

    private static AgentSession session() {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        session.setBizId("biz");
        session.setVersion(0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));
        return session;
    }

    private static int count(String table) {
        Integer value = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(1) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private static void createTable(JdbcTemplate jdbc, String table) {
        jdbc.execute("CREATE TABLE " + table + " ("
                + "USER_ID VARCHAR(128) NOT NULL, SESSION_ID VARCHAR(191) NOT NULL,"
                + "BIZ_ID VARCHAR(128), VERSION BIGINT NOT NULL, MESSAGES_JSON CLOB,"
                + "TITLE VARCHAR(255), MESSAGE_COUNT INT DEFAULT 0,"
                + "TOTAL_TOKENS BIGINT DEFAULT 0,"
                + "COMPACTION_LEASE_UNTIL BIGINT,"
                + "UPDATED_AT TIMESTAMP, PRIMARY KEY (USER_ID, SESSION_ID))");
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
        public AgentSessionStore customStore() {
            return new InMemoryAgentSessionStore(10, 60);
        }
    }
}

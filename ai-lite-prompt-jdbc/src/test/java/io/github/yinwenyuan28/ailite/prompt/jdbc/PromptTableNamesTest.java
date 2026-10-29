package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptAudit;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptKey;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStatus;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptTableNames;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提示词三张表都能自定义表名。
 *
 * <p>回归重点：{@link AiLitePromptJdbcAutoConfiguration} 曾固定调用带默认表名的两参
 * 构造器，配置层没有任何入口能把表名传进去；三个表名虽然写在构造器参数里，用户
 * 却只能用默认值。三张表是一个整体（发布要写版本表、指针表和审计表），所以测试
 * 走完整发布流程，而不是只断言字段。</p>
 */
public class PromptTableNamesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLitePromptJdbcAutoConfiguration.class));

    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        jdbc = new JdbcTemplate(sharedDataSource());
        // 同一个库在多个测试间复用，先清掉上一个测试留下的表。
        for (String table : new String[]{"MY_PROMPT_VERSION", "MY_PROMPT_ACTIVE", "MY_PROMPT_AUDIT",
                "AI_LITE_PROMPT_VERSION", "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /**
     * 三张表全部换成业务前缀后，草稿、发布、读生效版本、审计都落在新表上。
     */
    @Test
    public void allThreeTablesWorkWithCustomNames() {
        createTables(jdbc, "MY_PROMPT_VERSION", "MY_PROMPT_ACTIVE", "MY_PROMPT_AUDIT");

        JdbcPromptStore store = new JdbcPromptStore(jdbc,
                "MY_PROMPT_VERSION", "MY_PROMPT_ACTIVE", "MY_PROMPT_AUDIT", null);
        PromptManager manager = manager(store);

        PromptVersion draft = manager.createDraft("resume-parse", "解析 {{resume}}", null, null, "alice");
        manager.publish(draft.getVersionId(), "bob");

        assertThat(store.findActive("resume-parse")).isPresent();
        assertThat(store.findActive("resume-parse").get().getSystemTemplate()).isEqualTo("解析 {{resume}}");
        assertThat(count(jdbc, "MY_PROMPT_VERSION")).isEqualTo(1);
        assertThat(count(jdbc, "MY_PROMPT_ACTIVE")).isEqualTo(1);
        // 发布会在审计表留痕，三个表都必须是自定义的那三张。
        assertThat(count(jdbc, "MY_PROMPT_AUDIT")).isGreaterThanOrEqualTo(1);

        // 默认表名一张都没被创建，说明没有回落到硬编码表名。
        for (String defaultTable : new String[]{"AI_LITE_PROMPT_VERSION",
                "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT"}) {
            assertThat(tableExists(jdbc, defaultTable)).isFalse();
        }
    }

    /**
     * 自动装配要把配置里的三个表名传下去，而不是走默认构造器。
     */
    @Test
    public void autoConfigurationPassesConfiguredTableNames() {
        createTables(jdbc, "MY_PROMPT_VERSION", "MY_PROMPT_ACTIVE", "MY_PROMPT_AUDIT");

        runner.withUserConfiguration(JdbcConfig.class)
                .withPropertyValues(
                        "ai-lite.prompt.jdbc.version-table=MY_PROMPT_VERSION",
                        "ai-lite.prompt.jdbc.active-table=MY_PROMPT_ACTIVE",
                        "ai-lite.prompt.jdbc.audit-table=MY_PROMPT_AUDIT")
                .run(context -> {
                    PromptStore store = context.getBean(PromptStore.class);
                    assertThat(store).isInstanceOf(PromptTableNames.class);

                    PromptTableNames names = (PromptTableNames) store;
                    assertThat(names.getVersionTable()).isEqualTo("MY_PROMPT_VERSION");
                    assertThat(names.getActiveTable()).isEqualTo("MY_PROMPT_ACTIVE");
                    assertThat(names.getAuditTable()).isEqualTo("MY_PROMPT_AUDIT");

                    PromptManager manager = manager(store);
                    PromptVersion draft = manager.createDraft("resume-parse", "v1 {{resume}}", null, null, "a");
                    manager.publish(draft.getVersionId(), "a");
                    assertThat(count(jdbc, "MY_PROMPT_VERSION")).isEqualTo(1);
                    assertThat(count(jdbc, "MY_PROMPT_ACTIVE")).isEqualTo(1);
                });
    }

    /**
     * 不配置时仍用原表名，老库升级不需要改任何东西。
     */
    @Test
    public void defaultsStayBackwardCompatible() {
        createTables(jdbc, "AI_LITE_PROMPT_VERSION", "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT");

        runner.withUserConfiguration(JdbcConfig.class).run(context -> {
            PromptStore store = context.getBean(PromptStore.class);
            PromptTableNames names = (PromptTableNames) store;
            assertThat(names.getVersionTable()).isEqualTo("AI_LITE_PROMPT_VERSION");
            assertThat(names.getActiveTable()).isEqualTo("AI_LITE_PROMPT_ACTIVE");
            assertThat(names.getAuditTable()).isEqualTo("AI_LITE_PROMPT_AUDIT");
        });
    }

    /**
     * 表名走 {@code [A-Za-z0-9_]+} 白名单，配错名字要在构造时就炸掉，不能拼进 SQL。
     */
    @Test
    public void illegalTableNameIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new JdbcPromptStore(jdbc, "MY_PROMPT_VERSION; DROP TABLE x",
                "MY_PROMPT_ACTIVE", "MY_PROMPT_AUDIT", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");
        assertThatThrownBy(() -> new JdbcPromptStore(jdbc, "MY_PROMPT_VERSION",
                "MY PROMPT ACTIVE", "MY_PROMPT_AUDIT", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 表名配错时读写要报错，而不是安静地什么都不做。
     */
    @Test
    public void wrongVersionTableFailsInsteadOfSilentlyReturningEmpty() {
        JdbcPromptStore store = new JdbcPromptStore(jdbc, "MISSING_PROMPT_VERSION",
                "MISSING_PROMPT_ACTIVE", "MISSING_PROMPT_AUDIT", null);
        assertThatThrownBy(() -> store.findActive("resume-parse"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static PromptManager manager(PromptStore store) {
        PromptKey key = new PromptKey("resume-parse", "简历解析",
                Collections.singletonList("resume"), "默认系统 {{resume}}", "默认用户");
        return new PromptManager(new PromptCatalog(Collections.singletonList(key)), store, 0L);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void createTables(JdbcTemplate jdbc, String versionTable, String activeTable,
                                     String auditTable) {
        jdbc.execute("CREATE TABLE " + versionTable + " ("
                + "VERSION_ID VARCHAR(64) PRIMARY KEY, PROMPT_KEY VARCHAR(64) NOT NULL,"
                + "VERSION_NO INT NOT NULL, STATUS VARCHAR(16) NOT NULL, SYSTEM_TEMPLATE CLOB,"
                + "USER_TEMPLATE CLOB, OUTPUT_SCHEMA CLOB, CONTENT_HASH VARCHAR(64),"
                + "CREATED_BY VARCHAR(64), CREATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE " + activeTable + " ("
                + "PROMPT_KEY VARCHAR(64) PRIMARY KEY, VERSION_ID VARCHAR(64) NOT NULL,"
                + "REVISION INT NOT NULL, PUBLISH_TIME TIMESTAMP, ACTOR VARCHAR(64))");
        jdbc.execute("CREATE TABLE " + auditTable + " ("
                + "AUDIT_ID VARCHAR(64) PRIMARY KEY, PROMPT_KEY VARCHAR(64) NOT NULL,"
                + "ACTION VARCHAR(16), VERSION_ID VARCHAR(64), ACTOR VARCHAR(64),"
                + "DETAIL VARCHAR(1000), CREATE_TIME TIMESTAMP)");
    }

    /**
     * 测试与容器里的自动装配必须用同一个库。
     *
     * <p>容器里另起一个内存库会看不到 {@code @Before} 建的表，测试就变成
     * "配的表名没生效"，而不是它本来要验证的行为。</p>
     *
     * @return 本测试类共享的内存数据源
     */
    private static synchronized JdbcDataSource sharedDataSource() {
        if (DATA_SOURCE == null) {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:prompt-names-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            DATA_SOURCE = dataSource;
        }
        return DATA_SOURCE;
    }

    private static JdbcDataSource DATA_SOURCE;

    /**
     * 提供与测试共用的 JdbcTemplate。
     */
    @Configuration
    static class JdbcConfig {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(sharedDataSource());
        }
    }
}

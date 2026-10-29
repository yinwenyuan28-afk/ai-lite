package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四张目录表都能自定义表名。
 *
 * <p>回归重点：{@code AI_LITE_ADMIN_SETTING} 曾经写死在 {@link JdbcCallBodyPolicy} 里，
 * 配了别的表名后读档位静默返回 none、写档位抛异常，管理员会以为"已开启全文记录"
 * 而实际一条都没记。</p>
 */
public class CatalogTableNamesTest {

    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("table-names");
    }

    /**
     * 四张表全部换成业务前缀后，目录与档位都能正常读写。
     */
    @Test
    public void allFourTablesWorkWithCustomNames() {
        createPrefixedTables(jdbc);

        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc,
                "MY_PROVIDER_OVERLAY", "MY_MODEL", "MY_BIZ_BINDING", 0);
        catalog.saveOverlay(new ProviderOverlay("demo", "http://x/v1", "k", null, null));
        assertThat(catalog.findProvider("demo").getBaseUrl()).isEqualTo("http://x/v1");
        assertThat(catalog.insertModelIfAbsent("demo", "qwen")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MY_MODEL", Integer.class))
                .isEqualTo(1);

        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(jdbc, "MY_ADMIN_SETTING");
        policy.save(CallBodyPolicy.ALL);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
        // 写入的确实是自定义表，默认表名没有被碰到。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MY_ADMIN_SETTING", Integer.class))
                .isEqualTo(1);
    }

    /**
     * 档位表配错时不能再"静默 none"：读不到要能区分"没建表"和"档位就是 none"。
     *
     * <p>行为约定：读失败返回 none（不能让可选功能把应用搞挂），但 save 必须报错，
     * 且错误消息里带出实际使用的表名，便于定位配置错误。</p>
     */
    @Test
    public void wrongSettingTableFailsLoudlyOnWriteAndNamesTheTable() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(jdbc, "MISSING_SETTING_TABLE");

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);
        assertThatThrownBy(() -> policy.save(CallBodyPolicy.ALL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_SETTING_TABLE");
    }

    /**
     * 默认表名保持不变，升级不会让既有库失效。
     */
    @Test
    public void defaultTableNameStaysBackwardCompatible() {
        jdbc.execute("CREATE TABLE AI_LITE_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");

        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(jdbc);
        assertThat(policy.getTable()).isEqualTo("AI_LITE_ADMIN_SETTING");
        policy.save(CallBodyPolicy.FAILURE);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.FAILURE);
    }

    /** 表名走同样的白名单校验，挡住身份不明的字符串。 */
    @Test
    public void illegalTableNameIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new JdbcCallBodyPolicy(jdbc, "my.setting"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");
        assertThatThrownBy(() -> new JdbcCallBodyPolicy(jdbc, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void createPrefixedTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE MY_PROVIDER_OVERLAY ("
                + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, BASE_URL VARCHAR(500), API_KEY VARCHAR(500),"
                + "CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT, UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE MY_MODEL ("
                + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT,"
                + "INPUT_MODES VARCHAR(64), CONTEXT_WINDOW INT, MAX_TOKENS INT,"
                + "THINKING_FORMAT VARCHAR(64), DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE MY_BIZ_BINDING ("
                + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL,"
                + "UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE MY_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");
    }
}

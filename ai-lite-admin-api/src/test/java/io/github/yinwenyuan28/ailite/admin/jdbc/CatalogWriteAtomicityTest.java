package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 目录写入的原子性。
 *
 * <p><b>回归背景</b>：{@code saveOverlay} 与 {@code saveBinding} 都是先 DELETE 再
 * INSERT，两条语句在自动提交下各自生效。INSERT 失败（约束冲突、列超长、连接断在
 * 中间）时 DELETE 已经落库，<b>这条配置直接消失</b>，管理页只看到一次 500，
 * 完全不知道数据已经没了。这是这一批里唯一会丢数据的缺陷。</p>
 *
 * <p>修法是把「删旧 + 插新」放进同一个事务。没有事务管理器时退回逐条提交，
 * 行为与升级前一致（不能因为拿不到事务管理器就让管理页用不了）。</p>
 *
 * <p><b>怎么造 INSERT 失败</b>：SQLite 不强制 VARCHAR 长度，靠截断造不出错。
 * 这里在表上加一条 CHECK 约束，专门拒绝某个标记值，INSERT 写这个值必然失败。</p>
 */
public class CatalogWriteAtomicityTest {

    private static final String BLOCKED_URL = "http://blocked/v1";
    private static final String BLOCKED_LEVEL = "blocked";

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("catalog-atomic");
        jdbc.execute("CREATE TABLE AI_LITE_PROVIDER_OVERLAY ("
                + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, API VARCHAR(64), BASE_URL VARCHAR(500),"
                + "API_KEY VARCHAR(500), CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT,"
                + "PROVIDER_HEADERS CLOB, PROVIDER_EXTRA_BODY CLOB, UPDATE_TIME TIMESTAMP,"
                // 这一列不许出现 BLOCKED_URL，用来稳定地制造 INSERT 失败。
                + "CHECK (BASE_URL IS NULL OR BASE_URL <> '" + BLOCKED_URL + "'))");
        jdbc.execute("CREATE TABLE AI_LITE_MODEL ("
                + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT,"
                + "INPUT_MODES VARCHAR(64), CONTEXT_WINDOW INT, MAX_TOKENS INT,"
                + "THINKING_FORMAT VARCHAR(64), DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE AI_LITE_BIZ_BINDING ("
                + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL,"
                + "UPDATE_TIME TIMESTAMP,"
                + "CHECK (THINKING_LEVEL <> '" + BLOCKED_LEVEL + "'))");
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /**
     * 覆盖写：INSERT 阶段失败时，原来的行必须还在。
     */
    @Test
    public void failedOverlayInsertKeepsPreviousRow() {
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, tx.getTransactionManager());
        catalog.saveOverlay(overlay("http://old/v1"));

        try {
            catalog.saveOverlay(overlay(BLOCKED_URL));
            fail("CHECK 约束拒绝的值写入必须失败");
        } catch (RuntimeException expected) {
            // 期望：INSERT 违反 CHECK。
        }

        // 关键断言：DELETE 必须一起回滚，旧行还在。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AI_LITE_PROVIDER_OVERLAY WHERE PROVIDER_ID = 'demo'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT BASE_URL FROM AI_LITE_PROVIDER_OVERLAY WHERE PROVIDER_ID = 'demo'",
                String.class)).isEqualTo("http://old/v1");
    }

    /**
     * 绑定写：INSERT 阶段失败时，原绑定必须还在。
     */
    @Test
    public void failedBindingInsertKeepsPreviousBinding() {
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, tx.getTransactionManager());
        prepareReadyModel(catalog);
        catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off"));

        try {
            catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", BLOCKED_LEVEL));
            fail("CHECK 约束拒绝的值写入必须失败");
        } catch (RuntimeException expected) {
            // 期望：INSERT 违反 CHECK。
        }

        assertThat(jdbc.queryForObject(
                "SELECT THINKING_LEVEL FROM AI_LITE_BIZ_BINDING WHERE BIZ_ID = 'demo-chat'",
                String.class)).isEqualTo("off");
    }

    /**
     * 档位写：INSERT 失败时，原档位必须还在。
     */
    @Test
    public void failedBodyLevelInsertKeepsPreviousLevel() {
        jdbc.execute("CREATE TABLE AI_LITE_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP, CHECK (SETTING_VALUE IS NULL"
                + " OR SETTING_VALUE <> '" + CallBodyPolicy.FAILURE + "'))");
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(jdbc, "AI_LITE_ADMIN_SETTING", 0,
                tx.getTransactionManager());
        policy.save(CallBodyPolicy.ALL);

        try {
            policy.save(CallBodyPolicy.FAILURE);
            fail("CHECK 约束拒绝的值写入必须失败");
        } catch (RuntimeException expected) {
            // 期望：INSERT 违反 CHECK。
        }

        assertThat(jdbc.queryForObject(
                "SELECT SETTING_VALUE FROM AI_LITE_ADMIN_SETTING WHERE SETTING_KEY = 'call-body'",
                String.class)).isEqualTo("all");
    }

    /**
     * 没有事务管理器时仍能正常读写，只是不保证原子性。
     *
     * <p>这条防的是「为了加事务把老构造器改坏」。</p>
     */
    @Test
    public void withoutTransactionManagerStillWorks() {
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        catalog.saveOverlay(overlay("http://x/v1"));

        assertThat(catalog.findProvider("demo").getBaseUrl()).isEqualTo("http://x/v1");
    }

    /**
     * 事务管理器为空时构造器不报错。
     */
    @Test
    public void nullTransactionManagerIsAccepted() {
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, null);

        assertThat(catalog.isActive()).isTrue();
    }

    /**
     * 业务规则错误在写事务之前就抛出，不能因为包了事务就变成别的东西。
     */
    @Test
    public void businessRuleFailureStillPropagates() {
        final JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, tx.getTransactionManager());

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                catalog.saveBinding(new BizBinding("x", "demo", "missing", "off"));
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("模型未就绪");
    }

    /**
     * 带事务的读路径要能正常返回三张表的内容。
     */
    @Test
    public void transactionalReadReturnsSnapshot() {
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, tx.getTransactionManager());
        prepareReadyModel(catalog);
        catalog.saveOverlay(overlay("http://x/v1"));
        catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off"));
        catalog.invalidate();

        assertThat(catalog.findProvider("demo").getBaseUrl()).isEqualTo("http://x/v1");
        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
        assertThat(catalog.findBinding("demo-chat").getModelId()).isEqualTo("qwen");
    }

    /**
     * @param baseUrl 覆盖地址
     * @return 覆盖对象
     */
    private static ProviderOverlay overlay(String baseUrl) {
        return new ProviderOverlay("demo", baseUrl, "k", Integer.valueOf(1000), Integer.valueOf(2000));
    }

    /**
     * 建一个参数齐全、已启用的模型。
     *
     * @param catalog 目录
     */
    private static void prepareReadyModel(JdbcRuntimeCatalog catalog) {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");
        catalog.setReady("demo", "qwen", true);
    }
}

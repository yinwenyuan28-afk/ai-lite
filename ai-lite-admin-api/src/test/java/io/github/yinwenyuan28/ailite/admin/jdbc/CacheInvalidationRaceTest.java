package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存失效的并发用例。
 *
 * <p><b>回归背景</b>：{@code invalidate()} 只把缓存置空，而读库的一方会在读完以后
 * 无条件把结果写回并重置有效期。读库线程与管理页保存撞在一起时，读库线程手里那份
 * 是旧值，写回后旧配置会再存活一整个缓存周期，于是出现「保存成功、刷新还是旧的」。</p>
 *
 * <p><b>为什么能稳定复现</b>：把 JdbcTemplate 包一层，让它在<b>已经把旧值拿到手、
 * 但还没返回给调用方</b>的位置停下来；主线程这时改库并触发失效，再放行。
 * 时序完全确定，不依赖线程调度运气。</p>
 */
public class CacheInvalidationRaceTest {

    /**
     * 目录：读库期间发生的保存与失效，不能被读回来的旧快照盖掉。
     */
    @Test
    public void catalogSaveDuringLoadDoesNotLeaveStaleSnapshot() throws Exception {
        final JdbcTemplate jdbc = SqliteFiles.open("catalog-race");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        jdbc.update("INSERT INTO AI_LITE_PROVIDER_OVERLAY (PROVIDER_ID, BASE_URL)"
                + " VALUES ('demo', 'http://old/v1')");

        final CountDownLatch oldRowsLoaded = new CountDownLatch(1);
        final CountDownLatch mayContinue = new CountDownLatch(1);
        final JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(
                pauseBeforeQuery(jdbc, "BIZ_BINDING", oldRowsLoaded, mayContinue),
                "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 60);

        final AtomicReference<ProviderOverlay> firstRead = new AtomicReference<ProviderOverlay>();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                firstRead.set(catalog.findProvider("demo"));
            }
        }, "catalog-reader");
        reader.start();

        assertThat(oldRowsLoaded.await(5, TimeUnit.SECONDS)).isTrue();
        // 覆盖表与模型表都已读进内存，此刻管理页保存并触发失效。
        jdbc.update("UPDATE AI_LITE_PROVIDER_OVERLAY SET BASE_URL = 'http://new/v1'"
                + " WHERE PROVIDER_ID = 'demo'");
        catalog.invalidate();
        mayContinue.countDown();
        reader.join(5000);

        // 正在进行的那一次读可以拿旧值（这个读取窗口无法消除），但缓存不能被它污染。
        assertThat(firstRead.get()).isNotNull();
        assertThat(catalog.findProvider("demo").getBaseUrl()).isEqualTo("http://new/v1");
    }

    /**
     * 档位：读库期间发生的保存，不能被读回来的旧档位盖掉。
     */
    @Test
    public void bodyLevelSaveDuringLoadDoesNotLeaveStaleLevel() throws Exception {
        final JdbcTemplate real = SqliteFiles.open("body-level-race");
        real.execute("CREATE TABLE AI_LITE_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");
        real.update("INSERT INTO AI_LITE_ADMIN_SETTING (SETTING_KEY, SETTING_VALUE)"
                + " VALUES ('call-body', 'none')");

        final CountDownLatch oldValueLoaded = new CountDownLatch(1);
        final CountDownLatch mayContinue = new CountDownLatch(1);
        final JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(
                pauseAfterQuery(real, oldValueLoaded, mayContinue), "AI_LITE_ADMIN_SETTING", 600);

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                policy.level();
            }
        }, "body-level-reader");
        reader.start();

        assertThat(oldValueLoaded.await(5, TimeUnit.SECONDS)).isTrue();
        // 读库线程已经把旧档位拿到手，此刻管理页改档位。
        policy.save(CallBodyPolicy.ALL);
        mayContinue.countDown();
        reader.join(5000);

        // 正在进行的那一次读可以返回旧档位，但下一次必须看到新值。
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
    }

    /**
     * 在指定表的查询<b>执行之前</b>停下来。
     *
     * <p>目录的 {@code read()} 按「覆盖表 → 模型表 → 绑定表」顺序查询，
     * 停在绑定表之前意味着前两张表已经读进内存，而读事务已经结束，
     * 主线程改库不会撞上 SQLite 的锁。</p>
     *
     * @param real        真实 JdbcTemplate
     * @param tableMarker SQL 里出现这个标记时暂停
     * @param arrived     已到停顿点
     * @param mayContinue 放行信号
     * @return 会暂停的 JdbcTemplate
     */
    private static JdbcTemplate pauseBeforeQuery(JdbcTemplate real, final String tableMarker,
                                                 final CountDownLatch arrived,
                                                 final CountDownLatch mayContinue) {
        return new JdbcTemplate(real.getDataSource()) {
            @Override
            public void query(String sql, RowCallbackHandler rch) {
                if (sql.contains(tableMarker)) {
                    arrived.countDown();
                    awaitQuietly(mayContinue);
                }
                super.query(sql, rch);
            }
        };
    }

    /**
     * 在查询<b>返回之后</b>停下来，此时结果已经拿到，只是还没交给调用方。
     *
     * @param real        真实 JdbcTemplate
     * @param arrived     已到停顿点
     * @param mayContinue 放行信号
     * @return 会暂停的 JdbcTemplate
     */
    private static JdbcTemplate pauseAfterQuery(JdbcTemplate real, final CountDownLatch arrived,
                                                final CountDownLatch mayContinue) {
        return new JdbcTemplate(real.getDataSource()) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                List<T> rows = super.queryForList(sql, elementType, args);
                arrived.countDown();
                awaitQuietly(mayContinue);
                return rows;
            }
        };
    }

    /**
     * @param latch 等待放行
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

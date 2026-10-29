package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用正文档位的读缓存。
 *
 * <p>回归背景：{@code LlmGateway.record} 在每次模型调用（含 agent 循环的每一轮）
 * 都会读一次档位，而 {@code level()} 原先每次都查库——等于每次调用白送一条
 * SELECT，可这个值几小时才改一次。</p>
 *
 * <p>这里用一个记录 SQL 的包装证明"第二次读不再打库"，同时守住三个语义：
 * 写档位后本机立即可见（不等 TTL）、配置 TTL 为 0 时退化成每次读库、
 * 读失败（表不存在）不会因为缓存而被永久固化。</p>
 */
public class CallBodyPolicyCacheTest {

    private JdbcTemplate jdbc;
    private CountingJdbc counting;

    @Before
    public void setUp() {
        JdbcTemplate real = SqliteFiles.open("call-body-cache");
        real.execute("CREATE TABLE AI_LITE_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");
        counting = new CountingJdbc(real);
        jdbc = real;
    }

    /**
     * 第一次读查库，第二次读命中缓存。
     *
     * <p>不用 {@code save} 造数据——它会顺带预热缓存（这是刻意的，见下一个
     * 测试），这里要验证的是"冷启动后连续读"的行为。</p>
     */
    @Test
    public void secondReadHitsCache() {
        jdbc.update("INSERT INTO AI_LITE_ADMIN_SETTING (SETTING_KEY, SETTING_VALUE)"
                + " VALUES ('call-body', 'all')");
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 60);
        counting.reset();

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
        int afterFirst = counting.count();
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
        int afterSecond = counting.count();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    /**
     * {@code save} 会直接写缓存，所以写入后的第一次读也不打库——
     * 这保证了"改完档位立刻生效"不依赖 TTL 过期。
     */
    @Test
    public void saveWarmsCacheWithoutExtraQuery() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 600);
        policy.save(CallBodyPolicy.ALL);
        counting.reset();

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
        assertThat(counting.count()).isZero();
    }

    /**
     * 写档位直接更新缓存，本机不必等 TTL 过期。
     */
    @Test
    public void saveUpdatesCacheImmediately() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 600);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);

        policy.save(CallBodyPolicy.FAILURE);

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.FAILURE);
    }

    /**
     * 缓存秒数为 0 时退化为每次读库，行为与引入缓存前一致。
     */
    @Test
    public void zeroCacheSecondsReadsEveryTime() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 0);
        policy.save(CallBodyPolicy.ALL);
        counting.reset();

        policy.level();
        policy.level();
        policy.level();

        assertThat(counting.count()).isEqualTo(3);
    }

    /**
     * 缓存了 none 也要正确命中，不能被当成"没缓存"。
     */
    @Test
    public void cachedNoneIsDistinguishableFromNoCache() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 60);
        // 表里没有这一行，读出来就是 none。
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);
        int afterFirst = counting.count();

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);
        assertThat(counting.count()).isEqualTo(afterFirst);
    }

    /**
     * 外部改了库里的值，在 TTL 内本机仍返回旧值（这是缓存的代价，需明确）。
     */
    @Test
    public void externalChangeIsNotVisibleUntilTtlExpires() {
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(counting, "AI_LITE_ADMIN_SETTING", 600);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);

        // 绕过 policy.save 直接改库，模拟另一个节点写入。
        jdbc.update("INSERT INTO AI_LITE_ADMIN_SETTING (SETTING_KEY, SETTING_VALUE)"
                + " VALUES ('call-body', 'all')");

        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);

        // 外部触发失效后立刻可见。
        policy.invalidate();
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.ALL);
    }

    /**
     * 表不存在时读失败返回 none，且不会被缓存成永久 none 之外的怪异状态；
     * 建表后（invalidate 一下）能读到真实值。
     */
    @Test
    public void missingTableDegradesToNoneAndRecovers() {
        JdbcTemplate noTable = SqliteFiles.open("call-body-cache-empty");
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(noTable, "MISSING_SETTING", 60);
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.NONE);

        noTable.execute("CREATE TABLE MISSING_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");
        noTable.update("INSERT INTO MISSING_SETTING (SETTING_KEY, SETTING_VALUE)"
                + " VALUES ('call-body', 'failure')");

        policy.invalidate();
        assertThat(policy.level()).isEqualTo(CallBodyPolicy.FAILURE);
    }

    /**
     * 记录 SQL 条数的 JdbcTemplate 包装。只计数，不改行为。
     */
    static final class CountingJdbc extends JdbcTemplate {

        private final List<String> statements = new ArrayList<String>();

        CountingJdbc(JdbcTemplate delegate) {
            super(delegate.getDataSource());
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            statements.add(sql);
            return super.queryForList(sql, elementType, args);
        }

        int count() {
            return statements.size();
        }

        void reset() {
            statements.clear();
        }
    }
}

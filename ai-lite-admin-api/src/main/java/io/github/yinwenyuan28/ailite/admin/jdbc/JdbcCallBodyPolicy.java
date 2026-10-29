package io.github.yinwenyuan28.ailite.admin.jdbc;


import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调用正文档位。没有设置行时视为不记录。
 *
 * <p>表名由 {@code ai-lite.catalog.setting-table} 配置，默认
 * {@code AI_LITE_ADMIN_SETTING}。</p>
 *
 * <p><b>为什么读失败只告警不抛</b>：这张表是可选功能，用户可能只跑了旧版
 * 建表脚本、或压根没打算用"记录正文"。读取档位把应用搞挂不合理。但也绝不能
 * 完全静默——表名配错与"还没建表"都会走到这里，若一声不响地返回
 * {@code none}，管理员会以为已经开了全文记录、实际一条都没记。因此首次失败时
 * 打一条带表名的 WARN，后续不重复刷屏。</p>
 */
public final class JdbcCallBodyPolicy implements CallBodySettings {

    private static final Logger log = LoggerFactory.getLogger(JdbcCallBodyPolicy.class);

    static final String KEY = "call-body";

    /** 档位缓存秒数默认值。档位几小时才改一次，没必要每次调用都问一次库。 */
    public static final int DEFAULT_CACHE_SECONDS = 10;

    private final JdbcTemplate jdbc;
    private final String table;
    /** 读失败只告警一次，避免每次取档位都刷日志。 */
    private final AtomicBoolean warned = new AtomicBoolean(false);
    /** 缓存毫秒数；小于等于 0 表示不缓存。 */
    private final long cacheMillis;
    /**
     * 缓存的档位。{@code null} 表示"没缓存"，与"缓存了 none"区分开——
     * 否则每次都会把 none 当成未命中。
     */
    private volatile String cachedLevel;
    private volatile long expiresAt;

    /**
     * 默认表名，供不经过配置的调用方（测试、手工装配）使用。
     *
     * @param jdbc JDBC
     */
    public JdbcCallBodyPolicy(JdbcTemplate jdbc) {
        this(jdbc, "AI_LITE_ADMIN_SETTING");
    }

    /**
     * @param jdbc  JDBC
     * @param table 设置表名，只允许字母、数字和下划线
     */
    public JdbcCallBodyPolicy(JdbcTemplate jdbc, String table) {
        this(jdbc, table, DEFAULT_CACHE_SECONDS);
    }

    /**
     * @param jdbc        JDBC
     * @param table       设置表名，只允许字母、数字和下划线
     * @param cacheSeconds 档位缓存秒数；小于等于 0 表示每次都读库
     */
    public JdbcCallBodyPolicy(JdbcTemplate jdbc, String table, int cacheSeconds) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        // 非法表名属于配置错误，装配期就失败，不要等到第一次读档位才发现。
        this.table = JdbcRuntimeCatalog.safeTable(table);
        this.cacheMillis = cacheSeconds <= 0 ? 0L : cacheSeconds * 1000L;
    }

    /**
     * @return 配置的表名
     */
    public String getTable() {
        return table;
    }

    @Override
    public String level() {
        String cached = cachedLevel;
        if (cached != null && System.currentTimeMillis() < expiresAt) {
            return cached;
        }
        String value = readLevel();
        if (cacheMillis > 0L) {
            cachedLevel = value;
            expiresAt = System.currentTimeMillis() + cacheMillis;
        }
        return value;
    }

    /**
     * 真正查库。读失败按不记录处理，但只告警一次——表名配错与"还没建表"
     * 都会走到这里，一声不响地返回 none 会让管理员误以为已经开了全文记录。
     *
     * @return none、failure 或 all
     */
    private String readLevel() {
        try {
            List<String> values = jdbc.queryForList(
                    "SELECT SETTING_VALUE FROM " + table + " WHERE SETTING_KEY = ?",
                    String.class, KEY);
            if (values.isEmpty() || values.get(0) == null) {
                return NONE;
            }
            String value = normalize(values.get(0));
            if (!NONE.equals(value) && !FAILURE.equals(value) && !ALL.equals(value)) {
                return NONE;
            }
            return value;
        } catch (RuntimeException ex) {
            if (warned.compareAndSet(false, true)) {
                log.warn("读取调用正文档位失败，按不记录处理。请确认表 {} 已建（ai-lite-catalog.sql）"
                                + "且 ai-lite.catalog.setting-table 配置正确: {}",
                        table, ex.toString());
            }
            return NONE;
        }
    }

    /**
     * @param level none、failure 或 all
     */
    public void save(String level) {
        String value = normalize(level);
        if (!NONE.equals(value) && !FAILURE.equals(value) && !ALL.equals(value)) {
            throw new IllegalArgumentException("记录档位只能是 none、failure 或 all");
        }
        JdbcRuntimeCatalog.requireTable(jdbc, table, "ai-lite-catalog.sql");
        jdbc.update("DELETE FROM " + table + " WHERE SETTING_KEY = ?", KEY);
        jdbc.update("INSERT INTO " + table + " (SETTING_KEY, SETTING_VALUE, UPDATE_TIME)"
                + " VALUES (?, ?, ?)", KEY, value, new Timestamp(System.currentTimeMillis()));
        // 写入成功后表确实存在，允许下次故障重新告警；同时把缓存直接改成新值，
        // 让本机的档位变更立刻生效，不必等 TTL 过期。
        warned.set(false);
        if (cacheMillis > 0L) {
            cachedLevel = value;
            expiresAt = System.currentTimeMillis() + cacheMillis;
        }
    }

    /**
     * 清掉本机缓存。多节点部署时可由外部在改完档位后广播触发。
     */
    public void invalidate() {
        cachedLevel = null;
        expiresAt = 0L;
    }

    static String normalize(String level) {
        return level == null ? NONE : level.trim().toLowerCase();
    }
}

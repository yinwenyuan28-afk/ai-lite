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

    private final JdbcTemplate jdbc;
    private final String table;
    /** 读失败只告警一次，避免每次取档位都刷日志。 */
    private final AtomicBoolean warned = new AtomicBoolean(false);

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
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        // 非法表名属于配置错误，装配期就失败，不要等到第一次读档位才发现。
        this.table = JdbcRuntimeCatalog.safeTable(table);
    }

    /**
     * @return 配置的表名
     */
    public String getTable() {
        return table;
    }

    @Override
    public String level() {
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
        // 写入成功后表确实存在，允许下次故障重新告警。
        warned.set(false);
    }

    static String normalize(String level) {
        return level == null ? NONE : level.trim().toLowerCase();
    }
}

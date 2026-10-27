package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

/**
 * 调用正文档位。没有设置行时视为不记录。
 */
public final class JdbcCallBodyPolicy implements CallBodyPolicy {

    static final String KEY = "call-body";

    private final JdbcTemplate jdbc;

    public JdbcCallBodyPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String level() {
        try {
            List<String> values = jdbc.queryForList(
                    "SELECT SETTING_VALUE FROM AI_LITE_ADMIN_SETTING WHERE SETTING_KEY = ?",
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
        JdbcRuntimeCatalog.requireTable(jdbc, "AI_LITE_ADMIN_SETTING", "ai-lite-catalog.sql");
        jdbc.update("DELETE FROM AI_LITE_ADMIN_SETTING WHERE SETTING_KEY = ?", KEY);
        jdbc.update("INSERT INTO AI_LITE_ADMIN_SETTING (SETTING_KEY, SETTING_VALUE, UPDATE_TIME)"
                + " VALUES (?, ?, ?)", KEY, value, new Timestamp(System.currentTimeMillis()));
    }

    static String normalize(String level) {
        return level == null ? NONE : level.trim().toLowerCase();
    }
}

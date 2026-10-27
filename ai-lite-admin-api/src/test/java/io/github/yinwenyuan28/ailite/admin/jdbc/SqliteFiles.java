package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;

/**
 * 自动测试用的 SQLite 文件。放在模块的 target/sqlite，不和 demo 的库混用。
 */
public final class SqliteFiles {

    private SqliteFiles() {
    }

    public static JdbcTemplate open(String name) {
        File dir = new File("target/sqlite");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 " + dir.getAbsolutePath());
        }
        File file = new File(dir, name + "-" + System.nanoTime() + ".db");
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法删除旧测试库 " + file.getAbsolutePath());
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/'));
        return new JdbcTemplate(dataSource);
    }
}

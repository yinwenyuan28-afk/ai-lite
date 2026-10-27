package io.github.yinwenyuan28.ailite.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 在目录 Bean 探测表之前把 schema.sql 跑完。Boot 的初始化要等到容器刷新结束才执行。
 */
@Configuration
public class DemoSqliteConfig {

    /**
     * @param environment 数据源地址，本地配置可以覆盖成绝对路径
     * @return SQLite 数据源
     */
    @Bean
    public DataSource dataSource(Environment environment) throws Exception {
        String url = environment.getProperty("spring.datasource.url", "jdbc:sqlite:data/ai-lite-demo.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        Connection connection = dataSource.getConnection();
        try {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } finally {
            connection.close();
        }
        return dataSource;
    }
}

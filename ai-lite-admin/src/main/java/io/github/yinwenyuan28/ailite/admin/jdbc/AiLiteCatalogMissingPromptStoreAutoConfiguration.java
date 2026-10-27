package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 有数据源但没有提示词 JDBC 存储。目录模式不能把发布落到内存里。
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnMissingClass("io.github.yinwenyuan28.ailite.prompt.jdbc.JdbcPromptStore")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLiteCatalogMissingPromptStoreAutoConfiguration {

    static final String MESSAGE =
            "目录模式需要引入 ai-lite-prompt-jdbc，并执行 ai-lite-prompt.sql 建表";

    /**
     * @return 不会返回；用来让启动失败
     */
    @Bean
    public Object aiLiteCatalogRequiresPromptJdbc() {
        throw new IllegalStateException(MESSAGE);
    }
}

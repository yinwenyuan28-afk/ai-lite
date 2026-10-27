package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 模块在类路径上，但进程没有数据源。目录不启用，避免静默把解析留在配置文件里还不说明。
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnMissingBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLiteCatalogNoJdbcAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiLiteCatalogNoJdbcAutoConfiguration.class);

    /**
     * 只打日志。没有 JdbcTemplate 时不注册目录。
     */
    public AiLiteCatalogNoJdbcAutoConfiguration() {
        log.warn("ai-lite-admin 在类路径上，但没有 JdbcTemplate，运行时目录未启用");
    }
}

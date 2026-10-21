package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC 会话存储自动装配。
 *
 * <p>顺序放在 Spring Boot 的 {@code JdbcTemplateAutoConfiguration} 之后，
 * 这样自动配置出来的 {@code JdbcTemplate} 也能被看到；同时放在 Starter 的
 * 会话自动配置之前，确保 JDBC 存储优先于默认内存存储。没有
 * {@code JdbcTemplate} 时本配置不生效，仍然回退到内存存储。</p>
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@AutoConfigureBefore(name = "io.github.yinwenyuan28.ailite.autoconfigure.AiLiteSessionAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(AiLiteSessionJdbcProperties.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLiteSessionJdbcAutoConfiguration {

    /**
     * @param jdbcTemplate JdbcTemplate
     * @param properties   表名等配置
     * @return JDBC 会话存储
     */
    @Bean
    @ConditionalOnMissingBean(AgentSessionStore.class)
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "ai-lite.session", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public AgentSessionStore aiLiteJdbcAgentSessionStore(JdbcTemplate jdbcTemplate,
                                                         AiLiteSessionJdbcProperties properties) {
        return new JdbcAgentSessionStore(jdbcTemplate, properties.getTableName());
    }
}

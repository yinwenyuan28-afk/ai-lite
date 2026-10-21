package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 提示词 JDBC 存储的自动装配。
 *
 * <p>只要类路径上有本模块，Starter 就不会创建内存存储；
 * 具体是 JDBC 还是调用方自定义的 Bean，由 {@code @ConditionalOnMissingBean} 决定。</p>
 */
@Configuration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLitePromptJdbcAutoConfiguration {

    /**
     * 有事务管理器时让发布/回滚在一个事务里完成。
     *
     * @param jdbcTemplate JdbcTemplate
     * @param transactionManager 事务管理器，可缺省
     * @return JDBC 存储
     */
    @Bean
    @ConditionalOnMissingBean(PromptStore.class)
    @ConditionalOnProperty(prefix = "ai-lite.prompt", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public PromptStore aiLitePromptStore(JdbcTemplate jdbcTemplate,
                                         ObjectProvider<PlatformTransactionManager> transactionManager) {
        return new JdbcPromptStore(jdbcTemplate, transactionManager.getIfAvailable());
    }
}

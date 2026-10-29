package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 提示词 JDBC 存储的自动装配。
 *
 * <p>只要类路径上有本模块，Starter 就不会创建内存存储；
 * 具体是 JDBC 还是调用方自定义的 Bean，由 {@code @ConditionalOnMissingBean} 决定。</p>
 *
 * <p>三张表名走 {@code ai-lite.prompt.jdbc.*-table}，默认值与 {@code ai-lite-prompt.sql}
 * 一致。这条链路此前是硬编码的：构造器虽然接受表名，但自动装配只会调用带默认值的
 * 两参版本，用户既没有配置项、也无法把自己的 Bean 塞进去（目录装配那边还会拿
 * 字面量再探一次表）。</p>
 *
 * <p>配置通过 setter 注入，而不是加在 {@link #aiLitePromptStore} 的参数上、也不是
 * 换成带参构造器：前者会让直接调用该方法的代码在升级后抛 {@code NoSuchMethodError}，
 * 后者会删掉隐式的无参构造器。两种都属二进制不兼容，由
 * {@code -Pcontract-check} 门禁拦下。</p>
 */
@Configuration
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(AiLitePromptJdbcProperties.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLitePromptJdbcAutoConfiguration {

    /** 未经过 Spring 注入时按默认表名工作，避免直接 new 出来就 NPE。 */
    private AiLitePromptJdbcProperties properties = new AiLitePromptJdbcProperties();

    /**
     * @param properties 三张表名
     */
    @Autowired
    public void setProperties(AiLitePromptJdbcProperties properties) {
        this.properties = properties == null ? new AiLitePromptJdbcProperties() : properties;
    }

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
        return new JdbcPromptStore(jdbcTemplate, properties.getVersionTable(),
                properties.getActiveTable(), properties.getAuditTable(),
                transactionManager.getIfAvailable());
    }
}

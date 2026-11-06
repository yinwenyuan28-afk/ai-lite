package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptTableNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 运行时目录。页面开关关闭时仍然注册，避免关掉页面就退回配置文件。
 *
 * <p>必须排在提示词自动装配之前，业务 id 才能先成为 {@link PromptCatalog}。
 * 必须排在提示词 JDBC 存储之后，才能确认发布会落库。</p>
 */
@Configuration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
        "io.github.yinwenyuan28.ailite.prompt.jdbc.AiLitePromptJdbcAutoConfiguration"
})
@AutoConfigureBefore(name = {
        "io.github.yinwenyuan28.ailite.autoconfigure.AiLitePromptAutoConfiguration",
        "io.github.yinwenyuan28.ailite.autoconfigure.AiLiteAutoConfiguration"
})
@ConditionalOnClass(name = "io.github.yinwenyuan28.ailite.prompt.jdbc.JdbcPromptStore")
@EnableConfigurationProperties({AiLiteCatalogProperties.class, AiLiteLlmBizProperties.class})
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLiteCatalogAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiLiteCatalogAutoConfiguration.class);

    /**
     * 有数据源时才建目录。Boot 2.1 不能在同一个元素上重复 {@code @ConditionalOnProperty}。
     */
    @Configuration
    @ConditionalOnBean(JdbcTemplate.class)
    static class CatalogBeans {

        /**
         * @param jdbc         JDBC
         * @param properties   表名与缓存
         * @param bizProperties 业务 id
         * @param environment  用来发现仍写在配置里的提示词 key
         * @return 运行时目录
         */
        @Bean
        @ConditionalOnMissingBean(RuntimeCatalog.class)
        public JdbcRuntimeCatalog aiLiteRuntimeCatalog(JdbcTemplate jdbc, AiLiteCatalogProperties properties,
                                                       AiLiteLlmBizProperties bizProperties,
                                                       Environment environment,
                                                       ObjectProvider<PlatformTransactionManager> transactions,
                                                       org.springframework.context.ApplicationContext context) {
            if (environment.getProperty("ai-lite.prompt.keys[0].key") != null) {
                log.warn("目录模式以业务 id 作为提示词，忽略 ai-lite.prompt.keys");
            }
            if (!hasPromptStore(context)) {
                throw new IllegalStateException(AiLiteCatalogMissingPromptStoreAutoConfiguration.MESSAGE);
            }
            CatalogBizIds.normalize(bizProperties.getBizIds());
            // 必须按容器里的 PromptStore 实际表名探测。以前这里写死
            // "AI_LITE_PROMPT_VERSION"，一旦用户用 ai-lite.prompt.jdbc.version-table
            // 改了名（或自带 PromptStore Bean），就会拿默认表名去探、探不到即启动失败，
            // 而错误消息又指回默认表名，用户完全看不出是自己配的表名没被采纳。
            requirePromptTable(context);
            ModelKeyLock lock = resolveModelKeyLock(properties, context);
            return new JdbcRuntimeCatalog(jdbc, properties.getProviderTable(), properties.getModelTable(),
                    properties.getBizTable(), properties.getCacheSeconds(),
                    resolveTransactionManager(transactions), lock);
        }

        /**
         * 按部署形态选择目录写锁。
         *
         * <p>单节点用进程内锁；多节点必须用 Redis 锁。多节点却拿不到 Redis 时
         * <b>直接启动失败</b>，不悄悄退回进程内锁——那样用户以为已经防住了重复写入，
         * 实际每个节点各锁各的，等于没锁。</p>
         *
         * @param properties 表名、缓存与部署形态
         * @param context    应用上下文，用来找 Redis 客户端
         * @return 写锁
         */
        private static ModelKeyLock resolveModelKeyLock(AiLiteCatalogProperties properties,
                                                        org.springframework.context.ApplicationContext context) {
            if (!properties.isMultiNode()) {
                log.info("目录写入使用进程内锁（单节点）。多节点部署请设 ai-lite.catalog.multi-node=true");
                return new LocalModelKeyLock();
            }
            StringRedisTemplate redis = null;
            try {
                redis = context.getBean(StringRedisTemplate.class);
            } catch (org.springframework.beans.BeansException ex) {
                redis = null;
            }
            if (redis == null) {
                throw new IllegalStateException("ai-lite.catalog.multi-node=true 但容器里没有 "
                        + "StringRedisTemplate。多节点部署必须引入 spring-data-redis 并配置 Redis，"
                        + "否则各节点会各自加锁，无法防止重复写入");
            }
            log.info("目录写入使用 Redis 锁（多节点），租期 {}ms，等待上限 {}ms",
                    Long.valueOf(properties.getLockLeaseMillis()),
                    Long.valueOf(properties.getLockWaitMillis()));
            return new RedisModelKeyLock(redis, properties.getLockLeaseMillis(),
                    properties.getLockWaitMillis());
        }

        /**
         * 取本应用的事务管理器。
         *
         * <p>用 {@code getIfUnique()} 而不是 {@code getIfAvailable()}：容器里有多个事务
         * 管理器时后者会抛 {@code NoUniqueBeanDefinitionException}，让应用直接起不来。
         * 这里拿不准就不包事务并打一条 WARN——管理页照常可用，只是失去原子性。</p>
         *
         * @param transactions 容器里的事务管理器
         * @return 事务管理器；无法确定时返回 null
         */
        private static PlatformTransactionManager resolveTransactionManager(
                ObjectProvider<PlatformTransactionManager> transactions) {
            PlatformTransactionManager unique = transactions.getIfUnique();
            if (unique == null && transactions.stream().count() > 1) {
                log.warn("容器里有多个事务管理器，无法确定用哪个，目录读写退回逐条提交。"
                        + "请给本应用的事务管理器加 @Primary，否则写入失败时配置可能丢失");
            }
            return unique;
        }

        /**
         * 确认提示词版本/指针表可用。
         *
         * <p>表名可能已被 {@code ai-lite.prompt.jdbc.version-table} 改掉，因此必须问
         * 存储自己用的是什么表名。版本表与指针表在 {@code findActive} 里 join 使用，
         * 只探版本表不够：缺指针表同样会让发布落不了库，只是错得更晚。</p>
         *
         * @param context 应用上下文，用来读存储上报的实际表名
         */
        private static void requirePromptTable(org.springframework.context.ApplicationContext context) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PromptStore store = context.getBean(PromptStore.class);
            if (store instanceof PromptTableNames) {
                PromptTableNames names = (PromptTableNames) store;
                JdbcRuntimeCatalog.requireTable(jdbc, names.getVersionTable(), "ai-lite-prompt.sql");
                JdbcRuntimeCatalog.requireTable(jdbc, names.getActiveTable(), "ai-lite-prompt.sql");
                return;
            }
            // 自定义 PromptStore 不自述表名时只能按默认表名探测。
            JdbcRuntimeCatalog.requireTable(jdbc, "AI_LITE_PROMPT_VERSION", "ai-lite-prompt.sql");
        }

        /**
         * 注册为 {@link CallBodySettings}：网关按 {@link CallBodyPolicy} 读档位，
         * 管理台按 {@link CallBodySettings} 写档位，两者必须是同一个实例、同一张表。
         *
         * @param jdbc       JDBC
         * @param properties 表名与档位缓存配置
         * @return 调用正文档位。表还没建时读取视为不记录
         */
        @Bean
        @ConditionalOnMissingBean(CallBodyPolicy.class)
        public CallBodySettings aiLiteCallBodyPolicy(JdbcTemplate jdbc,
                                                     AiLiteCatalogProperties properties,
                                                     ObjectProvider<PlatformTransactionManager> transactions) {
            return new JdbcCallBodyPolicy(jdbc, properties.getSettingTable(),
                    properties.getSettingCacheSeconds(),
                    resolveTransactionManager(transactions));
        }

        /**
         * @param bizProperties 业务 id
         * @return 以业务 id 为 key 的提示词目录
         */
        @Bean
        @ConditionalOnMissingBean(PromptCatalog.class)
        public PromptCatalog aiLitePromptCatalog(AiLiteLlmBizProperties bizProperties) {
            return CatalogBizIds.catalog(bizProperties.getBizIds());
        }

        private static boolean hasPromptStore(org.springframework.context.ApplicationContext context) {
            String[] names = context.getBeanNamesForType(PromptStore.class);
            for (String name : names) {
                Object bean = context.getBean(name);
                if (bean != null && bean.getClass().getName()
                        .equals("io.github.yinwenyuan28.ailite.prompt.jdbc.JdbcPromptStore")) {
                    return true;
                }
            }
            return false;
        }
    }
}

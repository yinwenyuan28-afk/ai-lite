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
import org.springframework.jdbc.core.JdbcTemplate;

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
        public RuntimeCatalog aiLiteRuntimeCatalog(JdbcTemplate jdbc, AiLiteCatalogProperties properties,
                                                   AiLiteLlmBizProperties bizProperties,
                                                   Environment environment,
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
            return new JdbcRuntimeCatalog(jdbc, properties.getProviderTable(), properties.getModelTable(),
                    properties.getBizTable(), properties.getCacheSeconds());
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
         * @param properties 表名配置
         * @return 调用正文档位。表还没建时读取视为不记录
         */
        @Bean
        @ConditionalOnMissingBean(CallBodyPolicy.class)
        public CallBodySettings aiLiteCallBodyPolicy(JdbcTemplate jdbc,
                                                     AiLiteCatalogProperties properties) {
            return new JdbcCallBodyPolicy(jdbc, properties.getSettingTable());
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

package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.prompt.InMemoryPromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词中心自动装配。
 *
 * <p>{@code ai-lite.enabled=false} 关闭整个模块；{@code ai-lite.prompt.enabled=false}
 * 只关闭提示词 Bean。没有引入 ai-lite-prompt-jdbc 时使用内存存储；引入后由 JDBC
 * 模块的自动装配提供共享存储，这里通过 {@code @ConditionalOnMissingClass} 让位。</p>
 */
@Configuration
@AutoConfigureAfter(AiLiteAutoConfiguration.class)
@EnableConfigurationProperties(AiLitePromptProperties.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLitePromptAutoConfiguration {

    /**
     * 提示词开关。Spring Boot 2.1 的 {@code @ConditionalOnProperty} 不可重复，
     * 所以把两级开关拆到嵌套配置里。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "ai-lite.prompt", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class PromptBeans {

        /**
         * @param properties 提示词配置
         * @return 提示词目录
         */
        @Bean
        @ConditionalOnMissingBean
        public PromptCatalog aiLitePromptCatalog(AiLitePromptProperties properties) {
            return new PromptCatalog(properties.getKeys());
        }

        /**
         * @return 内存存储；JDBC 模块存在时由该模块提供
         */
        @Bean
        @ConditionalOnMissingBean(PromptStore.class)
        @ConditionalOnMissingClass("io.github.yinwenyuan28.ailite.prompt.jdbc.JdbcPromptStore")
        public PromptStore aiLitePromptStore() {
            return new InMemoryPromptStore();
        }

        /**
         * @param catalog    提示词目录
         * @param store      提示词存储
         * @param properties 提示词配置
         * @return 提示词管理器
         */
        @Bean
        @ConditionalOnMissingBean
        public PromptManager aiLitePromptManager(PromptCatalog catalog, PromptStore store,
                                                 AiLitePromptProperties properties) {
            return new PromptManager(catalog, store, properties.getCacheSeconds());
        }
    }
}

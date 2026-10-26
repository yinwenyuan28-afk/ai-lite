package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
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
 * JDBC 调用记录存储的自动装配。
 *
 * <p>装配顺序有两个约束：</p>
 *
 * <ul>
 *   <li>{@code @AutoConfigureAfter(JdbcTemplateAutoConfiguration)}：等 Boot 把
 *       {@code JdbcTemplate} 准备好，否则 {@code @ConditionalOnBean} 看不到它。</li>
 *   <li>{@code @AutoConfigureBefore(AiLiteAutoConfiguration)}：starter 里有一个
 *       {@code @ConditionalOnMissingBean} 的 {@code NoopLlmCallStore} 兜底 Bean，
 *       若它先执行就会占位，导致本配置被让位。必须先注册 JDBC 实现。</li>
 * </ul>
 *
 * <p>{@code tableName} 缺失时启动失败（在 {@link #aiLiteJdbcCallStore} 里手写校验，
 * 不引入 bean validation 依赖）。调用记录写失败会被异步日志吞掉，静默失败比对
 * 用户更友好的地方在于：宁可启动就炸，也不要几周后才发现账单表是空的。</p>
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@AutoConfigureBefore(name = "io.github.yinwenyuan28.ailite.autoconfigure.AiLiteAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(AiLiteStoreJdbcProperties.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiLiteStoreJdbcAutoConfiguration {

    /**
     * 建表提示语。放在常量里，保证异常消息与文档口径一致。
     */
    static final String MISSING_TABLE_MESSAGE =
            "ai-lite.store.jdbc.table-name 必须显式配置（调用记录表名），"
            + "并确认已执行 ai-lite-llm-call.sql 建表。"
            + "不需要 JDBC 调用记录时可设 ai-lite.store.jdbc.enabled=false";

    /**
     * 存储开关。Boot 2.1 的 {@code @ConditionalOnProperty} 不可重复，
     * 因此把 {@code ai-lite.store.jdbc.enabled} 拆到嵌套配置里，
     * 与外层的 {@code ai-lite.enabled} 形成两级开关（与 prompt 模块写法一致）。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "ai-lite.store.jdbc", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class StoreBeans {

        /**
         * @param jdbcTemplate JdbcTemplate
         * @param properties   表名等配置
         * @return JDBC 调用记录存储
         * @throws IllegalStateException 未配置 table-name 时抛出
         */
        @Bean
        @ConditionalOnMissingBean(LlmCallStore.class)
        @ConditionalOnBean(JdbcTemplate.class)
        public LlmCallStore aiLiteJdbcCallStore(JdbcTemplate jdbcTemplate,
                                                AiLiteStoreJdbcProperties properties) {
            String tableName = properties.getTableName();
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new IllegalStateException(MISSING_TABLE_MESSAGE);
            }
            return new JdbcLlmCallStore(jdbcTemplate, tableName);
        }
    }
}

package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.Workflow;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.session.AgentSessionLoop;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.jdbc.AiLiteSessionJdbcAutoConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配：默认 Bean、用户覆盖和总开关。
 */
public class AiLiteAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AiLiteAutoConfiguration.class, AiLiteSessionAutoConfiguration.class))
            .withPropertyValues(
                    "ai-lite.llm.biz-ids[0]=demo",
                    "ai-lite.llm.providers.demo.api=openai-completions",
                    "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:1/v1",
                    "ai-lite.llm.providers.demo.api-key=test",
                    "ai-lite.llm.providers.demo.models[0].id=m",
                    "ai-lite.llm.profiles.main.provider=demo",
                    "ai-lite.llm.profiles.main.model=m",
                    "ai-lite.llm.profiles.main.thinking-level=off");

    @Test
    public void wiresDefaultBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LlmGateway.class);
            assertThat(context).hasSingleBean(AgentLoop.class);
            assertThat(context).hasSingleBean(Workflow.class);
            assertThat(context).hasSingleBean(ModelResolver.class);
            assertThat(context).hasSingleBean(AgentSessionLoop.class);
            assertThat(context).hasSingleBean(AgentSessionStore.class);
        });
    }

    @Test
    public void userCallStoreOverridesDefault() {
        runner.withUserConfiguration(UserConfig.class).run(context -> {
            assertThat(context).hasSingleBean(LlmCallStore.class);
            assertThat(context.getBean(LlmCallStore.class)).isInstanceOf(CustomStore.class);
        });
    }

    @Test
    public void jdbcSessionStoreWinsWhenJdbcTemplatePresent() {
        runner.withConfiguration(AutoConfigurations.of(AiLiteSessionJdbcAutoConfiguration.class))
                .withUserConfiguration(JdbcConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentSessionStore.class);
                    assertThat(context.getBean(AgentSessionStore.class).getClass().getName())
                            .isEqualTo("io.github.yinwenyuan28.ailite.session.jdbc.JdbcAgentSessionStore");
                });
    }

    @Test
    public void jdbcSessionStoreWinsWhenJdbcTemplateIsAutoConfigured() {
        runner.withConfiguration(AutoConfigurations.of(
                        JdbcTemplateAutoConfiguration.class,
                        AiLiteSessionJdbcAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcTemplate.class);
                    assertThat(context).hasSingleBean(AgentSessionStore.class);
                    assertThat(context.getBean(AgentSessionStore.class).getClass().getName())
                            .isEqualTo("io.github.yinwenyuan28.ailite.session.jdbc.JdbcAgentSessionStore");
                });
    }

    @Test
    public void globalSwitchDisablesEverything() {
        runner.withPropertyValues("ai-lite.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LlmGateway.class);
            assertThat(context).doesNotHaveBean(AgentSessionStore.class);
        });
    }

    /**
     * 提供 DataSource，让 JdbcTemplateAutoConfiguration 自己创建 JdbcTemplate。
     */
    @Configuration
    static class DataSourceConfig {
        @Bean
        public DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:session-auto-config-" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return dataSource;
        }
    }

    /**
     * 提供 JdbcTemplate，触发 JDBC 会话存储。
     */
    @Configuration
    static class JdbcConfig {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:session-order-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return new JdbcTemplate(dataSource);
        }
    }

    /**
     * 用户自定义 Bean。
     */
    @Configuration
    static class UserConfig {
        @Bean
        public LlmCallStore customStore() {
            return new CustomStore();
        }
    }

    /**
     * 便于断言类型的空实现。
     */
    static class CustomStore implements LlmCallStore {
        @Override
        public void insert(io.github.yinwenyuan28.ailite.llm.LlmCallRecord record) {
        }
    }
}

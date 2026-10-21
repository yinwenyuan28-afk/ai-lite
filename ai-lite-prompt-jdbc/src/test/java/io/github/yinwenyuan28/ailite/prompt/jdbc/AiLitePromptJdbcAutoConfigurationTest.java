package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.InMemoryPromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词 JDBC 自动装配：默认创建、总开关、模块开关和用户覆盖。
 */
public class AiLitePromptJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLitePromptJdbcAutoConfiguration.class))
            .withUserConfiguration(JdbcConfig.class);

    @Test
    public void createsJdbcStoreByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PromptStore.class);
            assertThat(context.getBean(PromptStore.class)).isInstanceOf(JdbcPromptStore.class);
        });
    }

    @Test
    public void globalSwitchDisablesStore() {
        runner.withPropertyValues("ai-lite.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PromptStore.class);
        });
    }

    @Test
    public void promptSwitchDisablesStore() {
        runner.withPropertyValues("ai-lite.prompt.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PromptStore.class);
        });
    }

    @Test
    public void userStoreOverridesDefault() {
        runner.withUserConfiguration(UserStoreConfig.class).run(context -> {
            assertThat(context).hasSingleBean(PromptStore.class);
            assertThat(context.getBean(PromptStore.class)).isInstanceOf(InMemoryPromptStore.class);
        });
    }

    /**
     * 提供一个可用的 JdbcTemplate。
     */
    @Configuration
    static class JdbcConfig {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:prompt-auto-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return new JdbcTemplate(dataSource);
        }
    }

    /**
     * 用户自定义的存储。
     */
    @Configuration
    static class UserStoreConfig {
        @Bean
        public PromptStore customPromptStore() {
            return new InMemoryPromptStore();
        }
    }
}

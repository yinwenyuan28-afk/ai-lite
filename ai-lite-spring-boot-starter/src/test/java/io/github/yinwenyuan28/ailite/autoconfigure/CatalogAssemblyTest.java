package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.admin.jdbc.AiLiteCatalogAutoConfiguration;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.jdbc.AiLitePromptJdbcAutoConfiguration;
import org.junit.Test;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录 Bean 接到网关解析器，业务 id 成为提示词目录。
 */
public class CatalogAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcTemplateAutoConfiguration.class,
                    AiLitePromptJdbcAutoConfiguration.class,
                    AiLiteCatalogAutoConfiguration.class,
                    AiLiteAutoConfiguration.class,
                    AiLitePromptAutoConfiguration.class))
            .withUserConfiguration(SchemaConfig.class)
            .withPropertyValues(
                    "ai-lite.llm.biz-ids[0]=demo-chat",
                    "ai-lite.llm.providers.demo.api=openai-completions",
                    "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:9/v1",
                    "ai-lite.llm.providers.demo.api-key=yml-key");

    @Test
    public void catalogResolvesBizAndOwnsPromptKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ModelResolver resolver = context.getBean(ModelResolver.class);
            assertThat(resolver.isCatalogActive()).isTrue();
            assertThat(context.getBean(PromptCatalog.class).find("demo-chat").isCatalogBound()).isTrue();

            JdbcRuntimeCatalog catalog = (JdbcRuntimeCatalog) context.getBean(
                    io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog.class);
            catalog.insertModelIfAbsent("demo", "qwen");
            catalog.saveCapabilities("demo", "qwen", false, 32768, 4096, "reasoning-effort");
            catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off"));

            assertThat(resolver.resolveBiz("demo-chat").getModelId()).isEqualTo("qwen");
            assertThat(resolver.resolveBiz("demo-chat").getApiKey()).isEqualTo("yml-key");
        });
    }

    @Test
    public void illegalBizIdFailsStartup() {
        runner.withPropertyValues("ai-lite.llm.biz-ids[0]=Bad_Id").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().getMessage()).contains("不符合提示词编码");
        });
    }

    /**
     * 建好目录表和提示词表，供自动装配探测。
     */
    @Configuration
    static class SchemaConfig {
        @Bean
        public DataSource dataSource() throws Exception {
            java.io.File dir = new java.io.File("target/sqlite");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建 " + dir.getAbsolutePath());
            }
            java.io.File file = new java.io.File(dir, "assembly-" + System.nanoTime() + ".db");
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/'));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE AI_LITE_PROVIDER_OVERLAY ("
                    + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, BASE_URL VARCHAR(500), API_KEY VARCHAR(500),"
                    + "CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT, UPDATE_TIME TIMESTAMP)");
            jdbc.execute("CREATE TABLE AI_LITE_MODEL ("
                    + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                    + "MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT,"
                    + "INPUT_MODES VARCHAR(64), CONTEXT_WINDOW INT, MAX_TOKENS INT,"
                    + "THINKING_FORMAT VARCHAR(64), DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
            jdbc.execute("CREATE TABLE AI_LITE_BIZ_BINDING ("
                    + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                    + "MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL,"
                    + "UPDATE_TIME TIMESTAMP)");
            jdbc.execute("CREATE TABLE AI_LITE_PROMPT_VERSION ("
                    + "VERSION_ID VARCHAR(64) PRIMARY KEY, PROMPT_KEY VARCHAR(64) NOT NULL,"
                    + "VERSION_NO INT NOT NULL, STATUS VARCHAR(16) NOT NULL, SYSTEM_TEMPLATE CLOB,"
                    + "USER_TEMPLATE CLOB, OUTPUT_SCHEMA CLOB, CONTENT_HASH VARCHAR(64),"
                    + "CREATED_BY VARCHAR(64), CREATE_TIME TIMESTAMP)");
            jdbc.execute("CREATE TABLE AI_LITE_PROMPT_ACTIVE ("
                    + "PROMPT_KEY VARCHAR(64) PRIMARY KEY, VERSION_ID VARCHAR(64) NOT NULL,"
                    + "REVISION INT NOT NULL, PUBLISH_TIME TIMESTAMP, ACTOR VARCHAR(64))");
            jdbc.execute("CREATE TABLE AI_LITE_PROMPT_AUDIT ("
                    + "AUDIT_ID VARCHAR(64) PRIMARY KEY, PROMPT_KEY VARCHAR(64) NOT NULL,"
                    + "ACTION VARCHAR(16), VERSION_ID VARCHAR(64), ACTOR VARCHAR(64),"
                    + "DETAIL VARCHAR(1000), CREATE_TIME TIMESTAMP)");
            return dataSource;
        }
    }
}

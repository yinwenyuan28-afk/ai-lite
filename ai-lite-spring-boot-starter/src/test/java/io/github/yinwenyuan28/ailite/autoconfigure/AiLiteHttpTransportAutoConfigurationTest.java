package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai-lite.http.transport} 的选择、默认值、缺模块和未知值。
 */
public class AiLiteHttpTransportAutoConfigurationTest {

    private static final String[] BASE = {
            "ai-lite.llm.biz-ids[0]=demo",
            "ai-lite.llm.providers.demo.api=openai-completions",
            "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:1/v1",
            "ai-lite.llm.providers.demo.api-key=test-key",
            "ai-lite.llm.providers.demo.models[0].id=m",
            "ai-lite.llm.profiles.main.provider=demo",
            "ai-lite.llm.profiles.main.model=m",
            "ai-lite.llm.profiles.main.thinking-level=off"
    };

    private static ApplicationContextRunner runner(String... extra) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiLiteAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues(BASE);
        return extra.length == 0 ? runner : runner.withPropertyValues(extra);
    }

    @Test
    public void defaultsToOkHttp() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(HttpExecutor.class);
            assertThat(context.getBean(HttpExecutor.class).getClass().getName())
                    .isEqualTo("io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor");
        });
    }

    @Test
    public void transportNameIsCaseInsensitive() {
        runner("ai-lite.http.transport=OKHTTP").run(context -> {
            assertThat(context).hasSingleBean(HttpExecutor.class);
            assertThat(context.getBean(HttpExecutor.class).getClass().getName())
                    .isEqualTo("io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor");
        });
    }

    @Test
    public void selectsJdk8() {
        runner("ai-lite.http.transport=jdk8").run(context -> {
            assertThat(context).hasSingleBean(HttpExecutor.class);
            assertThat(context.getBean(HttpExecutor.class).getClass().getName())
                    .isEqualTo("io.github.yinwenyuan28.ailite.http.jdk8.Jdk8HttpExecutor");
        });
    }

    @Test
    public void selectsJetty() {
        runner("ai-lite.http.transport=jetty").run(context -> {
            assertThat(context).hasSingleBean(HttpExecutor.class);
            assertThat(context.getBean(HttpExecutor.class).getClass().getName())
                    .isEqualTo("io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor");
        });
    }

    @Test
    public void missingTransportModuleFailsWithActionableMessage() {
        runner("ai-lite.http.transport=webclient").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(messages(context.getStartupFailure()))
                    .contains("ai-lite-http-webclient")
                    .contains("HttpExecutor");
        });
    }

    @Test
    public void missingJettyModuleFailsFast() {
        runner("ai-lite.http.transport=jetty")
                .withClassLoader(new FilteredClassLoader(
                        "io.github.yinwenyuan28.ailite.http.jetty"))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(messages(context.getStartupFailure()))
                            .contains("ai-lite-http-jetty");
                });
    }

    @Test
    public void unknownTransportFailsAndListsChoices() {
        runner("ai-lite.http.transport=typo").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(messages(context.getStartupFailure()))
                    .contains("不支持")
                    .contains("okhttp")
                    .contains("jetty")
                    .contains("ktor-cio");
        });
    }

    @Test
    public void reporterDoesNotBreakNormalWiring() {
        runner("ai-lite.http.transport=jetty").run(context -> {
            assertThat(context).hasSingleBean(HttpTransportReporter.class);
            assertThat(context).hasSingleBean(LlmGateway.class);
        });
    }

    private static String messages(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}

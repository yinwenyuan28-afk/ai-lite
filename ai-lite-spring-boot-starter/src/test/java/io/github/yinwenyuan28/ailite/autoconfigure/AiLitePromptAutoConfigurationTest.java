package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.prompt.InMemoryPromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import io.github.yinwenyuan28.ailite.prompt.RenderedPrompt;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词自动装配：配置绑定、默认存储、用户覆盖和开关。
 */
public class AiLitePromptAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLitePromptAutoConfiguration.class))
            .withPropertyValues(
                    "ai-lite.prompt.cache-seconds=0",
                    "ai-lite.prompt.keys[0].key=resume-parse",
                    "ai-lite.prompt.keys[0].title=简历解析",
                    "ai-lite.prompt.keys[0].placeholders[0]=resume",
                    "ai-lite.prompt.keys[0].default-system-template=系统 {{resume}}",
                    "ai-lite.prompt.keys[0].default-user-template=岗位");

    @Test
    public void wiresDefaultBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PromptCatalog.class);
            assertThat(context).hasSingleBean(PromptStore.class);
            assertThat(context).hasSingleBean(PromptManager.class);
            assertThat(context.getBean(PromptStore.class)).isInstanceOf(InMemoryPromptStore.class);
            assertThat(context.getBean(PromptCatalog.class).keys()).hasSize(1);
            assertThat(context.getBean(PromptCatalog.class).keys().get(0).getTitle())
                    .isEqualTo("简历解析");
        });
    }

    @Test
    public void rendersConfiguredDefaults() {
        runner.run(context -> {
            PromptManager manager = context.getBean(PromptManager.class);
            RenderedPrompt rendered = manager.render("resume-parse",
                    Collections.singletonMap("resume", "五年 Java"));
            assertThat(rendered.getSystem()).isEqualTo("系统 五年 Java");
            assertThat(rendered.getUser()).isEqualTo("岗位");
        });
    }

    @Test
    public void managerCanPublishAndRollback() {
        runner.run(context -> {
            PromptManager manager = context.getBean(PromptManager.class);
            PromptVersion draft = manager.createDraft("resume-parse",
                    "新 {{resume}}", null, null, "alice");
            manager.publish(draft.getVersionId(), "alice");

            Optional<PromptVersion> active = manager.current("resume-parse");
            assertThat(active).isPresent();
            assertThat(active.get().getVersionId()).isEqualTo(draft.getVersionId());
        });
    }

    @Test
    public void userStoreOverridesDefault() {
        runner.withUserConfiguration(UserConfig.class).run(context -> {
            assertThat(context).hasSingleBean(PromptStore.class);
            assertThat(context.getBean(PromptStore.class)).isSameAs(UserConfig.STORE);
        });
    }

    @Test
    public void disabledSwitchRemovesBeans() {
        runner.withPropertyValues("ai-lite.prompt.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PromptManager.class);
            assertThat(context).doesNotHaveBean(PromptCatalog.class);
        });
    }

    @Test
    public void globalSwitchRemovesPromptBeans() {
        runner.withPropertyValues("ai-lite.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PromptManager.class);
            assertThat(context).doesNotHaveBean(PromptCatalog.class);
            assertThat(context).doesNotHaveBean(PromptStore.class);
        });
    }

    @Test
    public void invalidKeyFailsFast() {
        runner.withPropertyValues("ai-lite.prompt.keys[0].key=BAD_KEY").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("小写字母");
        });
    }

    @Test
    public void duplicateKeyFailsFast() {
        runner.withPropertyValues(
                "ai-lite.prompt.keys[1].key=resume-parse",
                "ai-lite.prompt.keys[1].default-system-template=x").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("重复");
        });
    }

    /**
     * 用户自定义存储。
     */
    @Configuration
    static class UserConfig {

        static final PromptStore STORE = new InMemoryPromptStore();

        @Bean
        public PromptStore customPromptStore() {
            return STORE;
        }
    }
}

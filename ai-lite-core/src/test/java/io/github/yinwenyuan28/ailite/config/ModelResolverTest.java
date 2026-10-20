package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置解析：profile、业务 id、思考档和密钥。
 */
public class ModelResolverTest {

    @Test
    public void profileResolvesProviderModelAndThinking() {
        ModelResolver resolver = new ModelResolver(io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config("key"));

        ResolvedModel model = resolver.resolveProfile("main");

        assertThat(model.getProvider()).isEqualTo("test");
        assertThat(model.getApi()).isEqualTo("fake");
        assertThat(model.getModelId()).isEqualTo("m");
        assertThat(model.getThinkingLevel()).isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(model.getMaxTokens()).isEqualTo(4096);
        assertThat(model.isAcceptsImage()).isFalse();
    }

    @Test
    public void explicitOffClampsToOff() {
        ModelResolver resolver = new ModelResolver(io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config("key"));

        ResolvedModel model = resolver.resolveExplicit("test", "m", "off");

        assertThat(model.getThinkingLevel()).isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void bizIdMustBeRegistered() {
        ModelResolver resolver = new ModelResolver(io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config("key"));

        assertThat(resolver.allows("biz")).isTrue();
        assertThat(resolver.allows("other")).isFalse();
    }

    @Test
    public void missingKeyFailsOnResolveNotOnConstruction() {
        ModelResolver resolver = new ModelResolver(io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config(null));

        assertThatThrownBy(() -> resolver.resolveProfile("main"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    @Test
    public void unknownProfileFails() {
        ModelResolver resolver = new ModelResolver(io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config("key"));

        assertThatThrownBy(() -> resolver.resolveProfile("nope"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("未知配置档");
    }

    @Test
    public void profilePointingToMissingModelFailsAtConstruction() {
        AiLiteConfig.Llm config = io.github.yinwenyuan28.ailite.llm.GatewayTestSupport.config("key");
        config.getProfiles().get("main").setModel("missing");

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("指向未知模型");
    }
}

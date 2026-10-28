package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import org.junit.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时目录参与解析时的绑定、未就绪模型和覆盖。
 */
public class ModelResolverCatalogTest {

    @Test
    public void activeCatalogAllowsProviderWithoutModels() {
        ModelResolver resolver = new ModelResolver(bareLlm(), 60_000L, catalog(true, null, ready("qwen"), null));

        assertThat(resolver.isCatalogActive()).isTrue();
    }

    @Test
    public void inactiveCatalogStillRequiresModels() {
        assertThatThrownBy(() -> new ModelResolver(bareLlm(), 60_000L, catalog(false, null, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("没有配置模型");
    }

    @Test
    public void missingBindingFailsWithoutProfileFallback() {
        AiLiteConfig.Llm llm = bareLlm();
        llm.setDefaultProfile("main");
        ModelResolver resolver = new ModelResolver(llm, 60_000L, catalog(true, null, ready("qwen"), null));

        assertThatThrownBy(() -> resolver.resolveBiz("demo-chat"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务未配置模型: demo-chat");
    }

    @Test
    public void notReadyModelIsRejected() {
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ModelResolver resolver = new ModelResolver(bareLlm(), 60_000L,
                catalog(true, binding, notReady("qwen"), null));

        assertThatThrownBy(() -> resolver.resolveBiz("demo-chat"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型尚未补齐能力参数: qwen");
    }

    @Test
    public void overlayReplacesEndpointAndSingleKey() {
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ProviderOverlay overlay = new ProviderOverlay("demo", "http://override/v1", "overlay-key",
                Integer.valueOf(2000), Integer.valueOf(3000));
        ModelResolver resolver = new ModelResolver(bareLlm(), 60_000L,
                catalog(true, binding, ready("qwen"), overlay));

        ResolvedModel resolved = resolver.resolveBiz("demo-chat");

        assertThat(resolved.getModelId()).isEqualTo("qwen");
        assertThat(resolved.getBaseUrl()).isEqualTo("http://override/v1");
        assertThat(resolved.getApiKey()).isEqualTo("overlay-key");
        assertThat(resolved.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(resolved.getReadTimeoutMs()).isEqualTo(3000);
    }

    @Test
    public void keyPoolIgnoresOverlayKey() {
        AiLiteConfig.Llm llm = bareLlm();
        llm.getProviders().get("demo").getApiKeys().add("pool-key");
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ProviderOverlay overlay = new ProviderOverlay("demo", null, "overlay-key", null, null);
        ModelResolver resolver = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"), overlay));

        assertThat(resolver.resolveBiz("demo-chat").getApiKey()).isEqualTo("pool-key");
    }

    @Test
    public void explicitProfileStillUsesYamlWhenCatalogIsActive() {
        AiLiteConfig.Llm llm = bareLlm();
        AiLiteConfig.Model yamlModel = new AiLiteConfig.Model();
        yamlModel.setId("yaml-model");
        llm.getProviders().get("demo").getModels().add(yamlModel);
        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("demo");
        profile.setModel("yaml-model");
        profile.setThinkingLevel("off");
        llm.getProfiles().put("main", profile);
        ModelResolver resolver = new ModelResolver(llm, 60_000L, catalog(true, null, ready("qwen"), null));

        assertThat(resolver.resolveProfile("main").getModelId()).isEqualTo("yaml-model");
    }

    private static AiLiteConfig.Llm bareLlm() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:8000/v1");
        provider.setApiKey("yml-key");
        llm.getProviders().put("demo", provider);
        return llm;
    }

    private static CatalogModel ready(String id) {
        return new CatalogModel("demo", id, true, false, Collections.singletonList("text"),
                Integer.valueOf(32768), Integer.valueOf(4096), "reasoning-effort", id, 1L);
    }

    private static CatalogModel notReady(String id) {
        return new CatalogModel("demo", id, false, false, null, null, null, null, id, 1L);
    }

    private static RuntimeCatalog catalog(final boolean active, final BizBinding binding,
                                          final CatalogModel model, final ProviderOverlay overlay) {
        return new RuntimeCatalog() {
            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public ProviderOverlay findProvider(String providerId) {
                return overlay;
            }

            @Override
            public CatalogModel findModel(String providerId, String modelId) {
                return model != null && modelId.equals(model.getModelId()) ? model : null;
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return binding != null && bizId.equals(binding.getBizId()) ? binding : null;
            }

            @Override
            public void invalidate() {
            }
        };
    }
}

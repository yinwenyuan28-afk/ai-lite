package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关对只带业务 id 的调用走目录，显式 profile 仍走配置文件。
 */
public class GatewayBizCatalogTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void bizCallUsesCatalogBinding() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.of(1, 1, 2), null));
        LlmGateway gateway = gateway(transport, resolver(binding(), ready(), overlay()));

        LlmResult result = gateway.one(LlmCall.biz("demo-chat", MESSAGES));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests()).hasSize(1);
        assertThat(transport.requests().get(0).getModel().getModelId()).isEqualTo("qwen");
        assertThat(transport.requests().get(0).getModel().getBaseUrl()).isEqualTo("http://override/v1");
    }

    @Test
    public void bizCallWithoutBindingDoesNotSend() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        LlmGateway gateway = gateway(transport, resolver(null, ready(), null));

        assertThatThrownBy(() -> gateway.one(LlmCall.biz("demo-chat", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务未配置模型");
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void profileCallStillUsesYamlModel() {
        AiLiteConfig.Llm llm = bare();
        AiLiteConfig.Model yamlModel = new AiLiteConfig.Model();
        yamlModel.setId("yaml-model");
        llm.getProviders().get("demo").getModels().add(yamlModel);
        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("demo");
        profile.setModel("yaml-model");
        profile.setThinkingLevel("off");
        llm.getProfiles().put("main", profile);
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.of(1, 1, 2), null));
        LlmGateway gateway = gateway(transport, new ModelResolver(llm, 60_000L,
                catalog(binding(), ready(), null)));

        gateway.one(LlmCall.profile("demo-chat", "main", MESSAGES));

        assertThat(transport.requests().get(0).getModel().getModelId()).isEqualTo("yaml-model");
    }

    private static LlmGateway gateway(GatewayTestSupport.FakeTransport transport, ModelResolver resolver) {
        return new LlmGateway(resolver, Collections.<LlmTransport>singletonList(transport),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static ModelResolver resolver(BizBinding binding, CatalogModel model, ProviderOverlay overlay) {
        return new ModelResolver(bare(), 60_000L, catalog(binding, model, overlay));
    }

    private static AiLiteConfig.Llm bare() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("fake");
        provider.setBaseUrl("http://127.0.0.1:8000/v1");
        provider.setApiKey("yml-key");
        llm.getProviders().put("demo", provider);
        return llm;
    }

    private static BizBinding binding() {
        return new BizBinding("demo-chat", "demo", "qwen", "off");
    }

    private static ProviderOverlay overlay() {
        return new ProviderOverlay("demo", "http://override/v1", "overlay-key", null, null);
    }

    private static CatalogModel ready() {
        return new CatalogModel("demo", "qwen", true, false, Collections.singletonList("text"),
                Integer.valueOf(32768), Integer.valueOf(4096), "reasoning-effort", "qwen", 1L);
    }

    private static RuntimeCatalog catalog(final BizBinding binding, final CatalogModel model,
                                          final ProviderOverlay overlay) {
        return new RuntimeCatalog() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public ProviderOverlay findProvider(String providerId) {
                return overlay;
            }

            @Override
            public CatalogModel findModel(String providerId, String modelId) {
                return model;
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return binding;
            }

            @Override
            public void invalidate() {
            }
        };
    }
}

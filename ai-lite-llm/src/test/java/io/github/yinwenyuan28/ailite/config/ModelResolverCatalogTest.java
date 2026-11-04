package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
                .hasMessageContaining("模型未就绪: qwen");
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

    /**
     * 协议可以由库内覆盖改掉，解析结果要跟着变；不覆盖时沿用配置文件。
     *
     * <p>这条是「管理页改供应商协议」能生效的前提：如果 {@code applyOverlay} 漏了
     * api，页面改完协议，请求仍然会打到旧协议的路径上。</p>
     */
    @Test
    public void overlayReplacesProtocol() {
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        AiLiteConfig.Llm llm = bareLlm();
        llm.getProviders().get("demo").setApi("openai-completions");

        ResolvedModel overridden = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"),
                        new ProviderOverlay("demo", null, null, null, null, "anthropic-messages")))
                .resolveBiz("demo-chat");
        assertThat(overridden.getApi()).isEqualTo("anthropic-messages");

        ResolvedModel fallback = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"),
                        new ProviderOverlay("demo", null, null, null, null, null)))
                .resolveBiz("demo-chat");
        assertThat(fallback.getApi()).isEqualTo("openai-completions");
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

    /* ---------------- 模型级请求参数：数据库有值 / NULL 回落 / 两边都空 ---------------- */

    /**
     * 数据库五列有值时，解析结果要带上它们，且与 YAML 路径一致。
     *
     * <p>这是本次改动的核心：修复前这五个字段在目录模式下全丢，
     * 同一模型在 {@code LlmCall.biz} 与 {@code LlmCall.explicit} 下发出去的请求不同。</p>
     */
    @Test
    public void catalogModelParamsReachResolvedModel() {
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        Map<String, String> levelMap = new LinkedHashMap<String, String>();
        levelMap.put("low", "low");
        levelMap.put("high", "high");
        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.put("temperature", Double.valueOf(0.2));
        Map<String, Map<String, Object>> byLevel = new LinkedHashMap<String, Map<String, Object>>();
        byLevel.put("high", Collections.<String, Object>singletonMap("top_p", Double.valueOf(0.95)));
        Map<String, String> modelHeaders = new LinkedHashMap<String, String>();
        modelHeaders.put("X-Model", "v2");
        Map<String, Object> extraBody = new LinkedHashMap<String, Object>();
        extraBody.put("vendor_field", "x");

        CatalogModel model = new CatalogModel("demo", "qwen", true, true,
                Collections.singletonList("text"), Integer.valueOf(32768), Integer.valueOf(4096),
                "deepseek", "qwen", 1L, levelMap, sampler, byLevel, modelHeaders, extraBody);
        ModelResolver resolver = new ModelResolver(bareLlm(), 60_000L,
                catalog(true, binding, model, null));

        ResolvedModel resolved = resolver.resolveBiz("demo-chat");

        assertThat(resolved.getThinkingLevelMap()).isEqualTo(levelMap);
        assertThat(resolved.getSamplerParams()).containsEntry("temperature", Double.valueOf(0.2));
        assertThat(resolved.getHeaders()).containsEntry("X-Model", "v2");
        assertThat(resolved.getExtraBody()).containsEntry("vendor_field", "x");
    }

    /**
     * 数据库列为 NULL 时，从配置文件里的同名模型继承。
     *
     * <p>升级路径：老库补列后全为 NULL，行为必须与升级前完全一致。</p>
     */
    @Test
    public void nullColumnsInheritYamlModelParams() {
        AiLiteConfig.Llm llm = bareLlm();
        AiLiteConfig.Model yamlModel = new AiLiteConfig.Model();
        yamlModel.setId("qwen");
        Map<String, String> levelMap = new LinkedHashMap<String, String>();
        levelMap.put("low", "low");
        yamlModel.setThinkingLevelMap(levelMap);
        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.put("temperature", Double.valueOf(0.9));
        yamlModel.setSamplerParams(sampler);
        Map<String, String> yamlHeaders = new LinkedHashMap<String, String>();
        yamlHeaders.put("X-From-Yaml", "yes");
        yamlModel.setHeaders(yamlHeaders);
        llm.getProviders().get("demo").getModels().add(yamlModel);

        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ModelResolver resolver = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"), null));

        ResolvedModel resolved = resolver.resolveBiz("demo-chat");

        assertThat(resolved.getThinkingLevelMap()).containsEntry("low", "low");
        assertThat(resolved.getSamplerParams()).containsEntry("temperature", Double.valueOf(0.9));
        assertThat(resolved.getHeaders()).containsEntry("X-From-Yaml", "yes");
    }

    /**
     * 数据库空 Map 表示「明确清空」，不再回落配置文件。
     */
    @Test
    public void emptyMapColumnClearsYamlValue() {
        AiLiteConfig.Llm llm = bareLlm();
        AiLiteConfig.Model yamlModel = new AiLiteConfig.Model();
        yamlModel.setId("qwen");
        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.put("temperature", Double.valueOf(0.9));
        yamlModel.setSamplerParams(sampler);
        llm.getProviders().get("demo").getModels().add(yamlModel);

        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        CatalogModel model = new CatalogModel("demo", "qwen", true, true,
                Collections.singletonList("text"), Integer.valueOf(32768), Integer.valueOf(4096),
                "reasoning-effort", "qwen", 1L,
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Map<String, Object>>(),
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, Object>());
        ModelResolver resolver = new ModelResolver(llm, 60_000L,
                catalog(true, binding, model, null));

        assertThat(resolver.resolveBiz("demo-chat").getSamplerParams()).isEmpty();
    }

    /**
     * 两级都没有这些参数时，结果为空且不抛空指针。
     */
    @Test
    public void bothSidesEmptyYieldsEmptyMaps() {
        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ModelResolver resolver = new ModelResolver(bareLlm(), 60_000L,
                catalog(true, binding, ready("qwen"), null));

        ResolvedModel resolved = resolver.resolveBiz("demo-chat");

        assertThat(resolved.getThinkingLevelMap()).isEmpty();
        assertThat(resolved.getSamplerParams()).isEmpty();
        assertThat(resolved.getHeaders()).isEmpty();
        assertThat(resolved.getExtraBody()).isEmpty();
    }

    /* ---------------- 供应商级请求参数 ---------------- */

    @Test
    public void overlayHeadersAndExtraBodyReplaceYamlWhenPresent() {
        AiLiteConfig.Llm llm = bareLlm();
        Map<String, String> yamlHeaders = new LinkedHashMap<String, String>();
        yamlHeaders.put("X-From-Yaml", "yes");
        llm.getProviders().get("demo").setHeaders(yamlHeaders);
        Map<String, Object> yamlExtra = new LinkedHashMap<String, Object>();
        yamlExtra.put("yaml_field", "yes");
        llm.getProviders().get("demo").setExtraBody(yamlExtra);

        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        Map<String, String> overlayHeaders = new LinkedHashMap<String, String>();
        overlayHeaders.put("X-From-Db", "db");
        Map<String, Object> overlayExtra = new LinkedHashMap<String, Object>();
        overlayExtra.put("db_field", "db");
        ProviderOverlay overlay = new ProviderOverlay("demo", null, null, null, null, null,
                overlayHeaders, overlayExtra);

        ModelResolver resolver = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"), overlay));
        ResolvedModel resolved = resolver.resolveBiz("demo-chat");

        assertThat(resolved.getHeaders()).containsEntry("X-From-Db", "db")
                .doesNotContainKey("X-From-Yaml");
        assertThat(resolved.getExtraBody()).containsEntry("db_field", "db")
                .doesNotContainKey("yaml_field");
    }

    @Test
    public void overlayNullHeadersKeepYaml() {
        AiLiteConfig.Llm llm = bareLlm();
        Map<String, String> yamlHeaders = new LinkedHashMap<String, String>();
        yamlHeaders.put("X-From-Yaml", "yes");
        llm.getProviders().get("demo").setHeaders(yamlHeaders);

        BizBinding binding = new BizBinding("demo-chat", "demo", "qwen", "off");
        ModelResolver resolver = new ModelResolver(llm, 60_000L,
                catalog(true, binding, ready("qwen"), new ProviderOverlay("demo", null, null, null, null)));

        assertThat(resolver.resolveBiz("demo-chat").getHeaders()).containsEntry("X-From-Yaml", "yes");
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

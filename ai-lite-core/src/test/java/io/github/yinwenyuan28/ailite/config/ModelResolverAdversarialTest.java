package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置解析的对抗性用例。
 *
 * <p>攻击面：空白/重复业务 id、缺失必填字段、悬空引用、密钥占位符、思考档回退、
 * 末尾斜杠和图片能力判定。</p>
 */
public class ModelResolverAdversarialTest {

    // ---------------------------------------------------------------- biz ids

    @Test
    public void blankAndDuplicateBizIdsAreIgnoredButCaseIsNot() {
        AiLiteConfig.Llm config = validConfig();
        config.setBizIds(Arrays.asList(null, "", "   ", " a ", "a", "b"));

        ModelResolver resolver = new ModelResolver(config);

        assertThat(resolver.allows("a")).isTrue();
        assertThat(resolver.allows(" a ")).isTrue();
        assertThat(resolver.allows("b")).isTrue();
        assertThat(resolver.allows("A")).isFalse();
        assertThat(resolver.allows("")).isFalse();
        assertThat(resolver.allows("   ")).isFalse();
        assertThat(resolver.allows(null)).isFalse();
    }

    @Test
    public void nullBizIdListMeansNothingIsAllowed() {
        AiLiteConfig.Llm config = validConfig();
        config.setBizIds(null);

        ModelResolver resolver = new ModelResolver(config);

        assertThat(resolver.allows("a")).isFalse();
    }

    // ------------------------------------------------------------- structure

    @Test
    public void providerWithBlankApiIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApi("   ");

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 api");
    }

    @Test
    public void providerWithoutApiFallsBackToOpenAiCompletions() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Provider provider = config.getProviders().get("test");
        provider.setApi("openai-completions");
        // 重新构造一个从未调用 setApi 的 provider，验证字段默认值。
        AiLiteConfig.Provider fresh = new AiLiteConfig.Provider();
        fresh.setBaseUrl("http://127.0.0.1:1/v1");
        fresh.setApiKey("test-key");
        fresh.setModels(new ArrayList<AiLiteConfig.Model>(provider.getModels()));
        config.getProviders().put("fresh", fresh);

        ResolvedModel model = new ModelResolver(config).resolveExplicit("fresh", "m", null);

        assertThat(model.getApi()).isEqualTo("openai-completions");
    }

    @Test
    public void providerWithBlankBaseUrlIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setBaseUrl("  ");

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 base-url");
    }

    @Test
    public void providerWithoutModelsIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setModels(new ArrayList<AiLiteConfig.Model>());

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("没有配置模型");
    }

    @Test
    public void nullProviderEntryIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().put("broken", null);

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("供应商配置不能为空");
    }

    @Test
    public void modelWithoutIdIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("   ");
        config.getProviders().get("test").setModels(Collections.singletonList(model));

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 id 的模型");
    }

    @Test
    public void nullModelEntryIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setModels(Collections.<AiLiteConfig.Model>singletonList(null));

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 id 的模型");
    }

    @Test
    public void profilePointingAtUnknownProviderIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("ghost");
        profile.setModel("m");
        config.getProfiles().put("bad", profile);

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("指向未知供应商");
    }

    @Test
    public void profilePointingAtUnknownModelIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProfiles().get("main").setModel("ghost");

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("指向未知模型");
    }

    @Test
    public void profileWithBlankProviderOrModelIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProfiles().get("main").setProvider(" ");
        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 provider");

        AiLiteConfig.Llm second = validConfig();
        second.getProfiles().get("main").setModel(" ");
        assertThatThrownBy(() -> new ModelResolver(second))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 model");
    }

    @Test
    public void nullProfileEntryIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProfiles().put("broken", null);

        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("配置档不能为空");
    }

    @Test
    public void resolveProfileTrimsReferencesAndRejectsUnknownProfile() {
        AiLiteConfig.Llm config = validConfig();
        config.getProfiles().get("main").setProvider("  test  ");
        config.getProfiles().get("main").setModel("  m  ");
        ModelResolver resolver = new ModelResolver(config);

        assertThat(resolver.resolveProfile("  main  ").getModelId()).isEqualTo("m");

        assertThatThrownBy(() -> resolver.resolveProfile("ghost"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("未知配置档");
        assertThatThrownBy(() -> resolver.resolveProfile("  "))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("配置档不能为空");
    }

    @Test
    public void resolveExplicitRejectsBlankProviderAndUnknownModel() {
        ModelResolver resolver = new ModelResolver(validConfig());

        assertThatThrownBy(() -> resolver.resolveExplicit(" ", "m", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("供应商不能为空");
        assertThatThrownBy(() -> resolver.resolveExplicit("test", "   ", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("未知模型");
    }

    @Test
    public void duplicateModelIdsSilentlyUseTheFirstEntry() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Model first = config.getProviders().get("test").getModels().get(0);
        first.setMaxTokens(Integer.valueOf(111));
        AiLiteConfig.Model duplicate = new AiLiteConfig.Model();
        duplicate.setId("m");
        duplicate.setMaxTokens(Integer.valueOf(222));
        config.getProviders().get("test").setModels(Arrays.asList(first, duplicate));

        ResolvedModel resolved = new ModelResolver(config).resolveExplicit("test", "m", null);

        // 配置层没有拒绝重复模型 id；解析时静默取第一个。此处固化当前行为，
        // 便于评审决定是否应当在启动期报错。
        assertThat(resolved.getMaxTokens()).isEqualTo(111);
    }

    // --------------------------------------------------------------- api key

    @Test
    public void literalApiKeyIsTrimmed() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("  test-key  ");

        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getApiKey())
                .isEqualTo("test-key");
    }

    @Test
    public void whitespaceOnlyApiKeyIsTreatedAsMissing() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("   ");
        ModelResolver resolver = new ModelResolver(config);

        assertThatThrownBy(() -> resolver.resolveExplicit("test", "m", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    @Test
    public void envPlaceholderResolvesExistingVariable() {
        String path = System.getenv("PATH");
        Assume.assumeTrue(path != null && !path.trim().isEmpty());

        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("${PATH}");

        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getApiKey())
                .isEqualTo(path.trim());
    }

    @Test
    public void envPlaceholderSupportsDefaultAfterColon() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("${AI_LITE_TEST_UNSET_9F3A:fallback-key}");

        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getApiKey())
                .isEqualTo("fallback-key");
    }

    @Test
    public void envPlaceholderWithoutDefaultFailsWhenVariableIsMissing() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("${AI_LITE_TEST_UNSET_9F3A}");
        ModelResolver resolver = new ModelResolver(config);

        assertThatThrownBy(() -> resolver.resolveExplicit("test", "m", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    @Test
    public void emptyPlaceholderIsRejected() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey("${}");
        ModelResolver resolver = new ModelResolver(config);

        assertThatThrownBy(() -> resolver.resolveExplicit("test", "m", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    @Test
    public void apiKeyEnvIsUsedWhenLiteralKeyIsMissing() {
        String path = System.getenv("PATH");
        Assume.assumeTrue(path != null && !path.trim().isEmpty());

        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey(null);
        config.getProviders().get("test").setApiKeyEnv("PATH");

        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getApiKey())
                .isEqualTo(path.trim());
    }

    @Test
    public void apiKeyEnvPointingAtMissingVariableFails() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setApiKey(null);
        config.getProviders().get("test").setApiKeyEnv("AI_LITE_TEST_UNSET_9F3A");
        ModelResolver resolver = new ModelResolver(config);

        assertThatThrownBy(() -> resolver.resolveExplicit("test", "m", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    // ------------------------------------------------------------- thinking

    @Test
    public void nullMappedLevelFallsBackToLowerSupportedLevel() {
        AiLiteConfig.Llm config = validConfig();
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("high", null);
        map.put("medium", "med");
        config.getProviders().get("test").getModels().get(0).setThinkingLevelMap(map);
        ModelResolver resolver = new ModelResolver(config);

        assertThat(resolver.resolveExplicit("test", "m", "high").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.MEDIUM);
        assertThat(resolver.resolveExplicit("test", "m", "max").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.MEDIUM);
        assertThat(resolver.resolveExplicit("test", "m", "low").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void unknownMapKeysDoNotEnableThinking() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").getModels().get(0)
                .setThinkingLevelMap(Collections.singletonMap("ultra", "u"));

        ResolvedModel resolved = new ModelResolver(config).resolveExplicit("test", "m", "high");

        assertThat(resolved.getThinkingLevel()).isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void reasoningFalseAlwaysClampsToOff() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").getModels().get(0).setReasoning(false);

        ResolvedModel resolved = new ModelResolver(config).resolveExplicit("test", "m", "high");

        assertThat(resolved.isReasoning()).isFalse();
        assertThat(resolved.getThinkingLevel()).isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void xhighAndMaxDegradeToHighWithoutMap() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").getModels().get(0)
                .setThinkingLevelMap(new LinkedHashMap<String, String>());
        ModelResolver resolver = new ModelResolver(config);

        assertThat(resolver.resolveExplicit("test", "m", "xhigh").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(resolver.resolveExplicit("test", "m", "max").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.HIGH);
    }

    @Test
    public void thinkingLevelParsingIsCaseInsensitiveButUnknownNamesFail() {
        ModelResolver resolver = new ModelResolver(validConfig());

        assertThat(resolver.resolveExplicit("test", "m", "HIGH").getThinkingLevel())
                .isEqualTo(LlmThinkingLevel.HIGH);
        assertThatThrownBy(() -> resolver.resolveExplicit("test", "m", "ultra"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("未知思考档");
    }

    // ------------------------------------------------------------ base url

    @Test
    public void allTrailingSlashesAreTrimmed() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setBaseUrl("  http://example.test/v1///  ");

        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getBaseUrl())
                .isEqualTo("http://example.test/v1");
    }

    @Test
    public void slashOnlyBaseUrlShouldBeRejectedAtStartup() {
        AiLiteConfig.Llm config = validConfig();
        config.getProviders().get("test").setBaseUrl("///");

        // 期望：结构校验就拒绝。实际：构造成功，resolved.baseUrl 为空串。
        assertThatThrownBy(() -> new ModelResolver(config))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("base-url");
    }

    // ------------------------------------------------------------- defaults

    @Test
    public void nonPositiveMaxTokensFallsBackTo8192() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Model model = config.getProviders().get("test").getModels().get(0);

        model.setMaxTokens(null);
        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getMaxTokens())
                .isEqualTo(8192);
        model.setMaxTokens(Integer.valueOf(0));
        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getMaxTokens())
                .isEqualTo(8192);
        model.setMaxTokens(Integer.valueOf(-5));
        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).getMaxTokens())
                .isEqualTo(8192);
    }

    @Test
    public void imageSupportRequiresExactLowercaseToken() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Model model = config.getProviders().get("test").getModels().get(0);

        model.setInput(Collections.singletonList("text"));
        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).isAcceptsImage())
                .isFalse();

        model.setInput(Arrays.asList("text", "image"));
        assertThat(new ModelResolver(config).resolveExplicit("test", "m", null).isAcceptsImage())
                .isTrue();
    }

    @Test
    public void providerAndModelHeadersAndExtraBodyAreMergedWithModelWinning() {
        AiLiteConfig.Llm config = validConfig();
        AiLiteConfig.Provider provider = config.getProviders().get("test");
        AiLiteConfig.Model model = provider.getModels().get(0);
        provider.getHeaders().put("X-A", "provider");
        provider.getHeaders().put("X-B", "provider");
        model.getHeaders().put("X-B", "model");
        provider.getExtraBody().put("top_p", Double.valueOf(0.1d));
        model.getExtraBody().put("top_p", Double.valueOf(0.9d));

        ResolvedModel resolved = new ModelResolver(config).resolveExplicit("test", "m", null);

        assertThat(resolved.getHeaders()).containsEntry("X-A", "provider")
                .containsEntry("X-B", "model");
        assertThat(resolved.getExtraBody()).containsEntry("top_p", Double.valueOf(0.9d));
    }

    // ------------------------------------------------------------- helpers

    private static AiLiteConfig.Llm validConfig() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(new ArrayList<String>(Collections.singletonList("a")));
        llm.setDefaultProfile("main");

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(true);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));
        model.setThinkingLevelMap(Collections.singletonMap("high", "high"));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        provider.setApiKey("test-key");
        provider.setModels(new ArrayList<AiLiteConfig.Model>(Collections.singletonList(model)));

        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        profile.setThinkingLevel("high");
        llm.getProfiles().put("main", profile);
        return llm;
    }
}

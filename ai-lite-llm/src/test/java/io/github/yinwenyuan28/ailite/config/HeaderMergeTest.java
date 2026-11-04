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
 * 两级请求头的合并规则：头名忽略大小写去重、保留头拒绝。
 *
 * <p>回归重点：修复前用 {@code putAll} 合并，{@code X-Foo} 与 {@code x-foo} 会变成
 * 两个头一起发出去，模型级的「覆盖」实际没生效；而在 {@code headers} 里写
 * {@code authorization} 会静默顶掉真实密钥。</p>
 */
public class HeaderMergeTest {

    @Test
    public void sameNameSameCaseModelWins() {
        ResolvedModel resolved = resolve(
                headers("X-Shared", "from-provider"),
                headers("X-Shared", "from-model"));

        assertThat(countIgnoreCase(resolved.getHeaders(), "X-Shared")).isEqualTo(1);
        assertThat(resolved.getHeaders().get("X-Shared")).isEqualTo("from-model");
    }

    /**
     * 头名大小写不同也算同名，只保留一个，且用模型级的写法与值。
     */
    @Test
    public void sameNameDifferentCaseModelWinsAndKeepsItsSpelling() {
        ResolvedModel resolved = resolve(
                headers("X-Request-Source", "from-provider"),
                headers("x-request-source", "from-model"));

        assertThat(countIgnoreCase(resolved.getHeaders(), "x-request-source")).isEqualTo(1);
        assertThat(resolved.getHeaders()).containsKey("x-request-source");
        assertThat(resolved.getHeaders()).doesNotContainKey("X-Request-Source");
        assertThat(resolved.getHeaders().get("x-request-source")).isEqualTo("from-model");
    }

    @Test
    public void differentNamesBothKept() {
        ResolvedModel resolved = resolve(
                headers("X-Provider-Only", "p"),
                headers("X-Model-Only", "m"));

        assertThat(resolved.getHeaders()).containsEntry("X-Provider-Only", "p")
                .containsEntry("X-Model-Only", "m");
    }

    @Test
    public void modelOnlyHeaderKeptWhenProviderHasNone() {
        ResolvedModel resolved = resolve(new LinkedHashMap<String, String>(),
                headers("X-Model-Only", "m"));

        assertThat(resolved.getHeaders()).containsEntry("X-Model-Only", "m");
    }

    /**
     * 写 authorization 会让真实密钥被顶掉，必须在装配期就拒绝。
     */
    @Test
    public void authorizationInModelHeadersRejected() {
        AiLiteConfig.Llm llm = llmWithModelHeaders(headers("authorization", "Token custom"));

        assertThatThrownBy(() -> new ModelResolver(llm, 60_000L, catalog()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Authorization")
                .hasMessageContaining("api-key");
    }

    @Test
    public void authorizationInProviderHeadersRejected() {
        AiLiteConfig.Llm llm = bareLlm();
        llm.getProviders().get("demo").setHeaders(headers("AUTHORIZATION", "Token custom"));

        assertThatThrownBy(() -> new ModelResolver(llm, 60_000L, catalog()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Authorization");
    }

    @Test
    public void contentTypeInModelHeadersRejected() {
        AiLiteConfig.Llm llm = llmWithModelHeaders(headers("content-type", "application/x-custom"));

        assertThatThrownBy(() -> new ModelResolver(llm, 60_000L, catalog()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Content-Type");
    }

    @Test
    public void normalHeaderAccepted() {
        AiLiteConfig.Llm llm = llmWithModelHeaders(headers("X-Trace-Id", "abc"));

        assertThat(new ModelResolver(llm, 60_000L, catalog()).isCatalogActive()).isTrue();
    }

    /* ---------------- 辅助 ---------------- */

    private static int countIgnoreCase(Map<String, String> headers, String name) {
        int count = 0;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, String> headers(String name, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(name, value);
        return map;
    }

    /**
     * 让供应商级与模型级各带一份头，解析出最终生效值。
     */
    private static ResolvedModel resolve(Map<String, String> providerHeaders,
                                         Map<String, String> modelHeaders) {
        AiLiteConfig.Llm llm = llmWithModelHeaders(modelHeaders);
        llm.getProviders().get("demo").setHeaders(providerHeaders);
        return new ModelResolver(llm, 60_000L, catalog()).resolveBiz("demo-chat");
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

    private static AiLiteConfig.Llm llmWithModelHeaders(Map<String, String> modelHeaders) {
        AiLiteConfig.Llm llm = bareLlm();
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("qwen");
        model.setHeaders(modelHeaders);
        llm.getProviders().get("demo").getModels().add(model);
        return llm;
    }

    /**
     * 目录里的模型行不带请求参数（全 null），让配置文件的同名模型提供头。
     */
    private static RuntimeCatalog catalog() {
        return new RuntimeCatalog() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public ProviderOverlay findProvider(String providerId) {
                return null;
            }

            @Override
            public CatalogModel findModel(String providerId, String modelId) {
                return new CatalogModel(providerId, modelId, true, false,
                        Collections.singletonList("text"), Integer.valueOf(32768),
                        Integer.valueOf(4096), "reasoning-effort", modelId, 1L);
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return new BizBinding(bizId, "demo", "qwen", "off");
            }

            @Override
            public void invalidate() {
            }
        };
    }
}

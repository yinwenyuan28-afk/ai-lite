package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验收：同一模型，走目录（{@code LlmCall.biz}）与走配置文件
 * （{@code LlmCall.explicit}）发出的请求头与请求体必须一致。
 *
 * <p>修复前两者不同——目录模式下模型级五个参数全丢，请求体里没有采样参数、
 * 请求头里没有模型级自定义头。这条用例是那次缺陷的回归防线。</p>
 */
public class ModelLevelParamsEndToEndTest {

    /** 记录最后一次出站请求，并返回一个合法的 OpenAI 补全响应。 */
    private static final class CapturingHttp implements HttpExecutor {
        String body;
        Map<String, String> headers;

        @Override
        public HttpResult execute(HttpRequest request) {
            body = request.getBody();
            headers = request.getHeaders();
            String json = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            return new HttpResult(200, json);
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /**
     * 配置相同时，两条路径的请求必须完全一致。
     *
     * <p>这里让数据库和配置文件给出同样的值。配合下面那条「值与配置文件不同时请求也跟着变」，
     * 合起来才能说明「两条路径等价」而不是「两边都空所以相等」。</p>
     */
    @Test
    public void catalogAndExplicitProduceSameRequest() throws Exception {
        CapturingHttp viaCatalog = new CapturingHttp();
        gateways(new ModelResolver(mirrorLlm(), 60_000L, mirrorCatalog()), viaCatalog)
                .one(LlmCall.biz("demo-chat",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("你好"))));

        CapturingHttp viaYaml = new CapturingHttp();
        gateways(new ModelResolver(mirrorLlm(), 60_000L, null), viaYaml)
                .one(LlmCall.explicit("demo-chat", "demo", "qwen", "high",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("你好"))));

        assertThat(viaCatalog.headers).isEqualTo(viaYaml.headers);
        assertThat(viaCatalog.body).isEqualTo(viaYaml.body);
    }

    /**
     * 数据库的值确实进了请求：与「配置文件里没有这些值」的基准相比，请求明显不同。
     *
     * <p>这条排除了「两条路径都丢参数、所以请求恰好一样」的可能。</p>
     */
    @Test
    public void catalogValuesActuallyReachRequest() throws Exception {
        CapturingHttp viaCatalog = new CapturingHttp();
        gateways(new ModelResolver(bareLlm(), 60_000L, distinctCatalog()), viaCatalog)
                .one(LlmCall.biz("demo-chat",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("你好"))));

        CapturingHttp baseline = new CapturingHttp();
        gateways(new ModelResolver(bareLlm(), 60_000L, bareCatalog()), baseline)
                .one(LlmCall.biz("demo-chat",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("你好"))));

        // 基准里两级参数都是空的。
        assertThat(baseline.headers).doesNotContainKey("X-Model").doesNotContainKey("X-Provider");
        assertThat(baseline.body).doesNotContain("temperature");

        // 数据库来源的请求里必须能看到两组参数。
        assertThat(viaCatalog.headers).containsEntry("X-Provider", "from-db")
                .containsEntry("X-Model", "from-db");
        assertThat(viaCatalog.body).contains("\"temperature\":0.2");
        assertThat(viaCatalog.body).contains("\"top_p\":0.95");
        assertThat(viaCatalog.body).contains("provider_field");
        assertThat(viaCatalog.body).contains("model_field");
        assertThat(viaCatalog.body).contains("\"reasoning_effort\":\"high\"");
    }

    /**
     * 目录行全是 NULL 时，请求要回到配置文件的内容——这就是升级后老库的行为。
     */
    @Test
    public void nullColumnsFallBackToYamlInRequest() throws Exception {
        RuntimeCatalog emptyCatalog = new RuntimeCatalog() {
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
                return new CatalogModel(providerId, modelId, true, true,
                        Collections.singletonList("text"), Integer.valueOf(8192),
                        Integer.valueOf(1000), "deepseek", "qwen", 1L);
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return new BizBinding(bizId, "demo", "qwen", "high");
            }

            @Override
            public void invalidate() {
            }
        };

        CapturingHttp http = new CapturingHttp();
        gateways(new ModelResolver(mirrorLlm(), 60_000L, emptyCatalog), http)
                .one(LlmCall.biz("demo-chat",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("你好"))));

        assertThat(http.headers).containsEntry("X-Provider", "from-yaml")
                .containsEntry("X-Model", "from-yaml");
        assertThat(http.body).contains("\"temperature\":0.9");
        assertThat(http.body).contains("provider_field");
        assertThat(http.body).contains("model_field");
    }

    /* ---------------- 辅助 ---------------- */

    private static LlmGateway gateways(ModelResolver resolver, HttpExecutor http) {
        List<LlmTransport> transports = new ArrayList<LlmTransport>();
        transports.add(new OpenAiCompletionsTransport(http));
        return new LlmGateway(resolver, transports, new NoopLlmCallStore(),
                new DirectExecutorProvider(), null);
    }

    /**
     * 配置文件里的供应商带两级参数，值与下面 {@link #mirrorCatalog()} 完全一致。
     */
    private static AiLiteConfig.Llm mirrorLlm() {
        return llm(true);
    }

    /**
     * 目录行与配置文件给出同样的值，用于验证两条路径等价。
     */
    private static RuntimeCatalog mirrorCatalog() {
        return catalogWith("from-yaml", Double.valueOf(0.9), Double.valueOf(0.5));
    }

    /**
     * 目录行给出与配置文件不同的值，用于验证数据库的值确实进了请求。
     */
    private static RuntimeCatalog distinctCatalog() {
        return catalogWith("from-db", Double.valueOf(0.2), Double.valueOf(0.95));
    }

    /**
     * 目录行与配置文件都不带任何请求参数，作为「没有配置」的基准。
     */
    private static RuntimeCatalog bareCatalog() {
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
                return new CatalogModel(providerId, modelId, true, true,
                        Collections.singletonList("text"), Integer.valueOf(8192),
                        Integer.valueOf(1000), "deepseek", "qwen", 1L);
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return new BizBinding(bizId, "demo", "qwen", "high");
            }

            @Override
            public void invalidate() {
            }
        };
    }

    /**
     * 构造一个带两级参数的目录，值由参数决定。
     *
     * @param tag       请求头与附加字段里的标记
     * @param temperature 采样温度
     * @param topP      高思考档的 top_p
     * @return 目录
     */
    private static RuntimeCatalog catalogWith(final String tag, final Double temperature,
                                              final Double topP) {
        return new RuntimeCatalog() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public ProviderOverlay findProvider(String providerId) {
                Map<String, String> headers = new LinkedHashMap<String, String>();
                headers.put("X-Provider", tag);
                Map<String, Object> extra = new LinkedHashMap<String, Object>();
                extra.put("provider_field", tag);
                return new ProviderOverlay(providerId, null, null, null, null,
                        null, headers, extra);
            }

            @Override
            public CatalogModel findModel(String providerId, String modelId) {
                Map<String, String> levelMap = new LinkedHashMap<String, String>();
                levelMap.put("low", "low");
                levelMap.put("high", "high");
                Map<String, Object> sampler = new LinkedHashMap<String, Object>();
                sampler.put("temperature", temperature);
                Map<String, Map<String, Object>> byLevel =
                        new LinkedHashMap<String, Map<String, Object>>();
                Map<String, Object> highParams = new LinkedHashMap<String, Object>();
                highParams.put("top_p", topP);
                byLevel.put("high", highParams);
                Map<String, String> headers = new LinkedHashMap<String, String>();
                headers.put("X-Model", tag);
                Map<String, Object> extra = new LinkedHashMap<String, Object>();
                extra.put("model_field", tag);
                return new CatalogModel(providerId, modelId, true, true,
                        Collections.singletonList("text"), Integer.valueOf(8192),
                        Integer.valueOf(1000), "deepseek", "qwen", 1L,
                        levelMap, sampler, byLevel, headers, extra);
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return new BizBinding(bizId, "demo", "qwen", "high");
            }

            @Override
            public void invalidate() {
            }
        };
    }

    /**
     * 只有供应商与模型骨架的配置：没有任何请求级参数。
     */
    private static AiLiteConfig.Llm bareLlm() {
        return llm(false);
    }

    /**
     * @param withParams true 表示带上两级请求参数
     * @return 配置
     */
    private static AiLiteConfig.Llm llm(boolean withParams) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:8000/v1");
        provider.setApiKey("k");

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("qwen");
        model.setReasoning(true);
        model.setThinkingFormat("deepseek");
        model.setContextWindow(Integer.valueOf(8192));
        model.setMaxTokens(Integer.valueOf(1000));

        if (withParams) {
            Map<String, String> providerHeaders = new LinkedHashMap<String, String>();
            providerHeaders.put("X-Provider", "from-yaml");
            provider.setHeaders(providerHeaders);
            Map<String, Object> providerExtra = new LinkedHashMap<String, Object>();
            providerExtra.put("provider_field", "from-yaml");
            provider.setExtraBody(providerExtra);

            Map<String, String> levelMap = new LinkedHashMap<String, String>();
            levelMap.put("low", "low");
            levelMap.put("high", "high");
            model.setThinkingLevelMap(levelMap);
            Map<String, Object> sampler = new LinkedHashMap<String, Object>();
            sampler.put("temperature", Double.valueOf(0.9));
            model.setSamplerParams(sampler);
            Map<String, Map<String, Object>> byLevel =
                    new LinkedHashMap<String, Map<String, Object>>();
            Map<String, Object> highParams = new LinkedHashMap<String, Object>();
            highParams.put("top_p", Double.valueOf(0.5));
            byLevel.put("high", highParams);
            model.setSamplerParamsByThinkingLevel(byLevel);
            Map<String, String> modelHeaders = new LinkedHashMap<String, String>();
            modelHeaders.put("X-Model", "from-yaml");
            model.setHeaders(modelHeaders);
            Map<String, Object> modelExtra = new LinkedHashMap<String, Object>();
            modelExtra.put("model_field", "from-yaml");
            model.setExtraBody(modelExtra);
        }

        provider.getModels().add(model);
        llm.getProviders().put("demo", provider);
        return llm;
    }
}

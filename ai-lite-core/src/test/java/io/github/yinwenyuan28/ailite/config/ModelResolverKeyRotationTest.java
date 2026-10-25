package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多 key 轮换（限流熔断计划 G4）：轮转顺序、游标仅在失败标记时推进、全冷却回退、
 * 冷却到期恢复、值匹配防乱标、占位符、api-keys 优先级，以及网关 429 触发轮换的
 * 端到端链路。key 值断言只出现在测试内存中。
 */
public class ModelResolverKeyRotationTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    /** 三 key 池 + 单模型的最小配置。 */
    private static AiLiteConfig.Llm config(String... apiKeys) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("biz"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(true);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("fake");
        provider.setBaseUrl("http://localhost");
        provider.setApiKeys(Arrays.asList(apiKeys));
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        llm.getProfiles().put("main", profile);
        return llm;
    }

    private static ModelResolver resolver(AiLiteConfig.Llm llm, long cooldownMs) {
        return new ModelResolver(llm, cooldownMs);
    }

    private static String resolveKey(ModelResolver resolver) {
        return resolver.resolveProfile("main").getApiKey();
    }

    @Test
    public void rotatesOnlyOnFailureMark() {
        ModelResolver resolver = resolver(config("k1", "k2", "k3"), 60_000L);
        // 无失败：重复 resolve 游标不动。
        assertThat(resolveKey(resolver)).isEqualTo("k1");
        assertThat(resolveKey(resolver)).isEqualTo("k1");

        resolver.markKeyFailure("test", "k1");
        assertThat(resolveKey(resolver)).isEqualTo("k2");
        resolver.markKeyFailure("test", "k2");
        assertThat(resolveKey(resolver)).isEqualTo("k3");
    }

    @Test
    public void allCooledFallsBackToFirst() {
        ModelResolver resolver = resolver(config("k1", "k2", "k3"), 60_000L);
        resolver.markKeyFailure("test", "k1");
        resolver.markKeyFailure("test", "k2");
        resolver.markKeyFailure("test", "k3");
        // 全部冷却：等价单 key，回第一个。
        assertThat(resolveKey(resolver)).isEqualTo("k1");
    }

    @Test
    public void cooldownExpiresLazilyAndKeyReturns() {
        ModelResolver resolver = resolver(config("k1", "k2"), 50L);
        resolver.markKeyFailure("test", "k1");
        assertThat(resolveKey(resolver)).isEqualTo("k2");
        try {
            Thread.sleep(80L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // 冷却到期（惰性判定）：k2 未失败仍在游标位；k1 恢复可见。
        assertThat(resolveKey(resolver)).isEqualTo("k2");
        resolver.markKeyFailure("test", "k2");
        assertThat(resolveKey(resolver)).isEqualTo("k1");
    }

    @Test
    public void unknownKeyValueDoesNotRotate() {
        ModelResolver resolver = resolver(config("k1", "k2"), 60_000L);
        resolver.markKeyFailure("test", "not-in-pool");
        resolver.markKeyFailure("test", null);
        resolver.markKeyFailure("no-such-provider", "k1");
        assertThat(resolveKey(resolver)).isEqualTo("k1");
    }

    @Test
    public void placeholdersResolveWithEnvFallback() {
        // env 缺失时走 ${VAR:default} 默认值——无需真实环境变量。
        ModelResolver resolver = resolver(
                config("${AI_LITE_NO_SUCH_ENV_A:pool-a}", "${AI_LITE_NO_SUCH_ENV_B:pool-b}"),
                60_000L);
        assertThat(resolveKey(resolver)).isEqualTo("pool-a");
        resolver.markKeyFailure("test", "pool-a");
        assertThat(resolveKey(resolver)).isEqualTo("pool-b");
    }

    @Test
    public void apiKeysTakePrecedenceOverSingleKey() {
        AiLiteConfig.Llm llm = config("pool-1", "pool-2");
        llm.getProviders().get("test").setApiKey("single");
        ModelResolver resolver = resolver(llm, 60_000L);
        assertThat(resolveKey(resolver)).isEqualTo("pool-1");
    }

    @Test
    public void allInvalidKeysIsConfigurationError() {
        AiLiteConfig.Llm llm = config("${AI_LITE_NO_SUCH_ENV_X}", "$AI_LITE_NO_SUCH_ENV_Y");
        final ModelResolver resolver = resolver(llm, 60_000L);
        try {
            resolver.resolveProfile("main");
            throw new AssertionError("应当抛出配置异常");
        } catch (LlmCallException ex) {
            assertThat(ex.getMessage()).contains("api-keys 全部无效");
        }
    }

    @Test
    public void singleKeyProviderStillWorks() {
        AiLiteConfig.Llm llm = config();
        llm.getProviders().get("test").setApiKey("solo");
        ModelResolver resolver = resolver(llm, 60_000L);
        assertThat(resolveKey(resolver)).isEqualTo("solo");
        // 单 key 无轮换池：失败标记是 no-op，不抛异常。
        resolver.markKeyFailure("test", "solo");
        assertThat(resolveKey(resolver)).isEqualTo("solo");
    }

    // ------------------------------------------------------------------
    // 网关集成：429 失败标记 → 下次 attempt 拿到新 key
    // ------------------------------------------------------------------

    @Test
    public void gateway429FailureRotatesKeyForNextAttempt() {
        ModelResolver modelResolver = resolver(config("k1", "k2", "k3"), 60_000L);
        final List<LlmRequest> requests = new ArrayList<LlmRequest>();
        LlmTransport transport = new LlmTransport() {
            private int calls;

            @Override
            public String api() {
                return "fake";
            }

            @Override
            public io.github.yinwenyuan28.ailite.llm.LlmCompletion complete(LlmRequest request) {
                requests.add(request);
                calls++;
                if (calls == 1) {
                    throw LlmCallException.httpError("rate limited", 429, null);
                }
                return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
            }
        };
        LlmGateway gateway = new LlmGateway(modelResolver,
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(),
                Executors.newSingleThreadScheduledExecutor());

        LlmResult result = gateway.oneWithRetry(LlmCall.profile("biz", "main", MESSAGES),
                RetryPolicy.builder().maxAttempts(2).initialBackoffMs(10L)
                        .maxBackoffMs(50L).jitter(false).build());
        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(requests).hasSize(2);
        // 第一次 attempt 用 k1（429 失败），重试 attempt 重新 resolve 拿到 k2。
        assertThat(requests.get(0).getModel().getApiKey()).isEqualTo("k1");
        assertThat(requests.get(1).getModel().getApiKey()).isEqualTo("k2");
    }
}

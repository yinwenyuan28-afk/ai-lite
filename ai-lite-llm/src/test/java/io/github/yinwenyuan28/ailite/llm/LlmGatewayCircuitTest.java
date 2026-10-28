package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.guard.ProviderGuard;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 网关熔断接入测试（限流熔断计划 G2）：失败口径经 verdict 直调
 * defaultRetryable、OPEN 快速失败零出站、半开探测恢复（异步/同步）、
 * PARSE 不计失败、guard 拒绝不回灌窗口、双 provider 隔离。
 */
public class LlmGatewayCircuitTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @After
    public void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * 剧本传输：按脚本依次返回补全或抛异常，同步异步共用；记录请求数用于
     * 断言 OPEN 期间零出站。
     */
    static final class ScriptedTransport implements LlmTransport {

        private final String api;
        final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<LlmRequest>());
        private final LinkedList<Object> script = new LinkedList<Object>();

        ScriptedTransport(String api) {
            this.api = api;
        }

        ScriptedTransport enqueue(LlmCompletion completion) {
            script.add(completion);
            return this;
        }

        ScriptedTransport failNext(RuntimeException failure) {
            script.add(failure);
            return this;
        }

        @Override
        public String api() {
            return api;
        }

        private Object next() {
            synchronized (script) {
                return script.isEmpty()
                        ? new LlmCompletion("default", null, LlmStopReason.STOP, LlmUsage.zero(), null)
                        : script.removeFirst();
            }
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            Object next = next();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            return (LlmCompletion) next;
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            requests.add(request);
            Object next = next();
            CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            if (next instanceof RuntimeException) {
                future.completeExceptionally((RuntimeException) next);
            } else {
                future.complete((LlmCompletion) next);
            }
            return future;
        }
    }

    /** 触发式熔断：1 样本、1% 阈值（任何可重试失败都开），OPEN 200ms 便于测试。 */
    private static ProviderGuard touchyGuard() {
        return new ProviderGuard("test", Integer.MAX_VALUE, 100, 10,
                new ProviderGuard.CircuitSettings(1, 60_000L, 1, 200L, 5_000L),
                System::currentTimeMillis, null, null);
    }

    private LlmGateway gateway(LlmTransport transport, ProviderGuard guard) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
        gateway.setGuards(Collections.singletonMap("test", guard));
        return gateway;
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.of(3, 2, 5), null);
    }

    private static final LlmCallException HTTP_500 =
            new LlmCallException("server error", null, LlmCallException.Kind.HTTP, 500);

    @Test
    public void asyncFailureOpensCircuitAndNextCallFailsFastWithoutRequest() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(HTTP_500)
                .enqueue(stop("never"));
        LlmGateway gateway = gateway(transport, touchyGuard());

        CompletableFuture<LlmResult> first = gateway.oneAsync(call());
        Throwable firstCause = assertFailure(first);
        assertThat(firstCause).isInstanceOfSatisfying(LlmCallException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(500));

        long started = System.currentTimeMillis();
        CompletableFuture<LlmResult> second = gateway.oneAsync(call());
        Throwable secondCause = assertFailure(second);
        assertThat(secondCause).isInstanceOfSatisfying(LlmCallException.class, ex ->
                assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        // OPEN 快速失败：毫秒级、零出站。
        assertThat(System.currentTimeMillis() - started).isLessThan(2000L);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void asyncProbeSuccessClosesCircuit() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(HTTP_500)
                .enqueue(stop("probe"));
        LlmGateway gateway = gateway(transport, touchyGuard());

        assertFailure(gateway.oneAsync(call()));
        assertThat(transport.requests).hasSize(1);

        // OPEN 冷却 200ms 期满 → 半开放行 1 个探测。
        Thread.sleep(260L);
        CompletableFuture<LlmResult> probe = gateway.oneAsync(call());
        assertThat(probe.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("probe");
        assertThat(transport.requests).hasSize(2);

        // 探测成功 → CLOSED：恢复正常放行。
        CompletableFuture<LlmResult> normal = gateway.oneAsync(call());
        assertThat(normal.get(5L, TimeUnit.SECONDS)).isNotNull();
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    public void syncFailureOpensCircuitAndSecondCallFailsFast() {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(HTTP_500);
        LlmGateway gateway = gateway(transport, touchyGuard());

        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(500));
        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void syncProbeSuccessClosesCircuit() {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(HTTP_500)
                .enqueue(stop("probe"));
        LlmGateway gateway = gateway(transport, touchyGuard());

        assertThatThrownBy(() -> gateway.one(call()));
        try {
            Thread.sleep(260L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(gateway.one(call()).getContent()).isEqualTo("probe");
        assertThat(gateway.one(call())).isNotNull();
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    public void parseFailureDoesNotOpenCircuit() {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(new LlmCallException("bad json", null, LlmCallException.Kind.PARSE))
                .enqueue(stop("fine"));
        LlmGateway gateway = gateway(transport, touchyGuard());

        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.PARSE));
        // PARSE 只计样本不计失败：不开闸，后续照常放行。
        assertThat(gateway.one(call()).getContent()).isEqualTo("fine");
        assertThat(transport.requests).hasSize(2);
    }

    /** guard 拒绝本身不是传输结局：连吃多次 CIRCUIT_OPEN 不应改变窗口/状态。 */
    @Test
    public void guardRejectionsDoNotFeedWindow() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("fake")
                .failNext(HTTP_500)
                .enqueue(stop("probe"))
                .enqueue(stop("after"));
        LlmGateway gateway = gateway(transport, touchyGuard());

        assertFailure(gateway.oneAsync(call()));
        for (int i = 0; i < 5; i++) {
            Throwable cause = assertFailure(gateway.oneAsync(call()));
            assertThat(cause).isInstanceOfSatisfying(LlmCallException.class, ex ->
                    assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        }
        assertThat(transport.requests).hasSize(1);

        // 多次拒绝没有延长 OPEN（窗口无回灌）：冷却期满后探测照常放行。
        Thread.sleep(260L);
        CompletableFuture<LlmResult> probe = gateway.oneAsync(call());
        assertThat(probe.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("probe");
    }

    /** 双 provider 隔离：一个 provider 的熔断不影响另一个（未配置 guard 直通）。 */
    @Test
    public void providersAreIsolated() {
        ScriptedTransport broken = new ScriptedTransport("fake").failNext(HTTP_500);
        ScriptedTransport healthy = new ScriptedTransport("fake2").enqueue(stop("other"));
        LlmGateway gateway = new LlmGateway(twoProviderResolver(),
                Arrays.asList(broken, healthy), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
        gateway.setGuards(Collections.singletonMap("test", touchyGuard()));

        assertThatThrownBy(() -> gateway.one(call()));
        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));

        LlmCall otherCall = LlmCall.profile("biz", "alt", MESSAGES);
        assertThat(gateway.one(otherCall).getContent()).isEqualTo("other");
        assertThat(broken.requests).hasSize(1);
        assertThat(healthy.requests).hasSize(1);
    }

    /** 双供应商配置：test/fake 与 other/fake2，profile main 与 alt。 */
    private static ModelResolver twoProviderResolver() {
        AiLiteConfig.Llm llm = GatewayTestSupport.config("key");

        AiLiteConfig.Model model2 = new AiLiteConfig.Model();
        model2.setId("m2");
        model2.setReasoning(true);
        model2.setInput(Collections.singletonList("text"));
        model2.setMaxTokens(Integer.valueOf(4096));
        model2.setThinkingLevelMap(Collections.singletonMap("high", "high"));

        AiLiteConfig.Provider provider2 = new AiLiteConfig.Provider();
        provider2.setApi("fake2");
        provider2.setBaseUrl("http://localhost");
        provider2.setApiKey("key2");
        provider2.setModels(Collections.singletonList(model2));
        llm.getProviders().put("other", provider2);

        AiLiteConfig.Profile profile2 = new AiLiteConfig.Profile();
        profile2.setProvider("other");
        profile2.setModel("m2");
        profile2.setThinkingLevel("high");
        llm.getProfiles().put("alt", profile2);
        return new ModelResolver(llm);
    }

    private static Throwable assertFailure(CompletableFuture<?> future) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(future.isDone()).isTrue();
        try {
            future.get();
            throw new AssertionError("应当异常完成");
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }
}

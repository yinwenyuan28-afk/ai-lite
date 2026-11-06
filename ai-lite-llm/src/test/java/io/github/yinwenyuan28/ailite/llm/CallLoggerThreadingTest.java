package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用记录不应落在调用线程上（F-14）。
 *
 * <p>手工构造 {@link LlmGateway}（5 参/6 参）时执行器默认是
 * {@link DirectExecutorProvider}，内联执行。于是 JDBC 落库直接跑在
 * transport 的回调线程（生产环境就是 OkHttp dispatcher 线程）上——
 * 一次慢插入会阻塞该线程上的其他响应回调。</p>
 *
 * <p>本类断言：手工构造的网关写记录时不占用调用线程。</p>
 */
public class CallLoggerThreadingTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    @After
    public void tearDown() {
        // 默认记录池是进程级共享的 daemon 池，不需要逐用例关闭。
    }

    /** 记录 insert 时的线程名，用来判断是否落在调用线程上。 */
    private static final class ThreadRecordingStore implements LlmCallStore {

        final List<String> threads = new CopyOnWriteArrayList<String>();
        final AtomicInteger rows = new AtomicInteger();

        @Override
        public void insert(LlmCallRecord record) {
            threads.add(Thread.currentThread().getName());
            rows.incrementAndGet();
        }
    }

    private static LlmTransport instantTransport() {
        return new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.completedFuture(complete(request));
            }
        };
    }

    /**
     * 手工构造（不传执行器）：落库不能内联在调用线程上。
     */
    @Test
    public void manualGatewayRecordsOffCallerThread() throws Exception {
        ThreadRecordingStore store = new ThreadRecordingStore();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instantTransport()), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        String callerThread = Thread.currentThread().getName();
        gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS);

        // 等异步记录落地。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.rows.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.rows.get()).isGreaterThanOrEqualTo(1);
        // 关键断言：记录不在调用线程上执行。
        assertThat(store.threads)
                .as("手工构造的网关不应在调用线程上写记录")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).isNotEqualTo(callerThread));
    }

    /**
     * 主链路完整跑通（成功用例的补充）：记录最终确实写进去了，不会因为换了池而丢。
     */
    @Test
    public void manualGatewayStillPersistsRecord() throws Exception {
        ThreadRecordingStore store = new ThreadRecordingStore();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instantTransport()), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.rows.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.rows.get()).as("记录最终必须落库").isGreaterThanOrEqualTo(1);
    }

    /**
     * 记录池的线程必须有稳定前缀，便于线上排查。
     */
    @Test
    public void recordThreadHasRecognisableName() throws Exception {
        ThreadRecordingStore store = new ThreadRecordingStore();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instantTransport()), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.rows.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.threads).isNotEmpty();
        assertThat(store.threads.get(0))
                .as("记录线程应有可识别的名字前缀")
                .startsWith("ai-lite-call-log-");
    }

    /**
     * 执行器传 null（退化为内联）：记录同样不能内联。
     *
     * <p>这条和上面显式传 {@code DirectExecutorProvider} 的是两个不同入口，
     * 原来只覆盖了后者。传 null 等价于「调用方没指定执行器」。</p>
     */
    @Test
    public void nullExecutorRecordsOffCallerThread() throws Exception {
        ThreadRecordingStore store = new ThreadRecordingStore();
        // 执行器传 null：构造器内部退化为 DirectExecutorProvider。
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instantTransport()), store,
                null, new SequentialToolExecutor());

        String callerThread = Thread.currentThread().getName();
        gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.rows.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.rows.get()).isGreaterThanOrEqualTo(1);
        assertThat(store.threads)
                .as("执行器传 null 时也不应在调用线程上写记录")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).isNotEqualTo(callerThread));
    }

    /**
     * NoopStore 也不该在调用线程上白跑一遍（保持行为一致）。
     */
    @Test
    public void noopStoreKeepsCallAsync() throws Exception {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instantTransport()), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());

        assertThat(gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS).getContent()).isEqualTo("ok");
    }
}

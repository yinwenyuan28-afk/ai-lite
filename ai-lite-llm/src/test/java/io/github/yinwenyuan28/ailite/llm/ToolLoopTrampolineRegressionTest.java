package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.guard.ProviderGuard;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具循环的推进结构：一轮接一轮不能靠「回调里再调自己」压栈（F-09）。
 *
 * <p>同步完成型 transport 与同步完成型 ToolExecutor 下，两层 {@code whenComplete}
 * 都是内联执行的，于是「模型完成 → 工具完成 → 下一轮」整条链压在同一根栈上，
 * 每轮两层。轮数上千就 {@code StackOverflowError}。</p>
 *
 * <p>本类用 {@code DirectExecutorProvider}（调用线程内联）加已完成的 future
 * 造出这条链，断言 {@code maxRounds} 上千时仍能正常结束，且改造没有改变
 * 每轮一条记录的语义。</p>
 */
public class ToolLoopTrampolineRegressionTest {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @After
    public void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * 同步完成型传输：每次都返回已完成的 future，并在达到 stopAfter 轮后改成 STOP。
     *
     * <p>{@code completeAsync} 返回 {@code completedFuture}，因此网关的
     * {@code whenComplete} 会在当前线程内联执行——这正是压栈的条件。</p>
     */
    private static final class AlwaysToolUseTransport implements LlmTransport {

        private final int stopAfter;
        private final AtomicInteger calls = new AtomicInteger();

        private AlwaysToolUseTransport(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            return respond();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            return CompletableFuture.completedFuture(respond());
        }

        private LlmCompletion respond() {
            int round = calls.incrementAndGet();
            if (round >= stopAfter) {
                return new LlmCompletion("done:" + round, null, LlmStopReason.STOP,
                        LlmUsage.zero(), null);
            }
            return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                    Collections.singletonList(new LlmToolCall("c" + round, "echo", "{}")));
        }
    }

    /** 同步完成型工具执行器：{@code executeAsync} 同样返回已完成的 future。 */
    private static final class InstantToolExecutor implements ToolExecutor {

        @Override
        public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
            List<String> results = new ArrayList<String>();
            for (int i = 0; i < calls.size(); i++) {
                results.add("ok");
            }
            return results;
        }

        @Override
        public CompletableFuture<List<String>> executeAsync(List<LlmTool> tools,
                                                            List<LlmToolCall> calls) {
            return CompletableFuture.completedFuture(execute(tools, calls));
        }
    }

    /** 记录每次插入的「结局#轮号」，用来断言记录条数与顺序。 */
    private static final class RecordingStore implements LlmCallStore {

        final List<String> records = new CopyOnWriteArrayList<String>();

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record.getStopReason() + "#" + record.getRoundNo());
        }
    }

    private static LlmTool echoTool() {
        return new LlmTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.<String, Object>emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "ok";
            }
        };
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi")));
    }

    private LlmGateway gateway(LlmTransport transport, LlmCallStore store,
                              ToolExecutor toolExecutor, ProviderGuard guard) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), toolExecutor, scheduler);
        if (guard != null) {
            gateway.setGuards(Collections.singletonMap("test", guard));
        }
        return gateway;
    }

    /**
     * 主用例：同步链路下 2000 轮不应栈溢出。
     *
     * <p>修复前每轮压两层栈，2000 轮必然 {@code StackOverflowError}；
     * 修复后推进由蹦床统一驱动，栈深恒定。</p>
     */
    @Test
    public void synchronousTransportWithLargeMaxRoundsDoesNotOverflow() throws Exception {
        int rounds = 2000;
        LlmGateway gateway = gateway(new AlwaysToolUseTransport(rounds),
                new NoopLlmCallStore(), new InstantToolExecutor(), null);

        LlmResult result = gateway.toolLoopAsync(call(),
                Collections.singletonList(echoTool()), Integer.valueOf(rounds))
                .get(30L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getContent()).isEqualTo("done:" + rounds);
    }

    /**
     * 在小栈线程上跑：栈深是否与轮数无关的直接证据。
     *
     * <p>这里把栈限制成 512KB（默认 8MB 的 1/16）。修复前 400 轮就会
     * {@code StackOverflowError}——而且它会被 {@code whenComplete} 的回调层捕获，
     * 表现为 future 永久挂起而不是抛异常，因此本用例用超时判失败。
     * 修复后栈深恒定，同样的轮数几百毫秒内结束。</p>
     */
    @Test
    public void smallStackThreadSurvivesManyRounds() throws Exception {
        final int rounds = 400;
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final java.util.concurrent.atomic.AtomicReference<LlmResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<LlmResult>();
        final ScheduledExecutorService shared = scheduler;
        Thread worker = new Thread(null, new Runnable() {
            @Override
            public void run() {
                try {
                    LlmGateway gateway = gateway(new AlwaysToolUseTransport(rounds),
                            new NoopLlmCallStore(), new InstantToolExecutor(), null);
                    outcome.set(gateway.toolLoopAsync(call(),
                            Collections.singletonList(echoTool()), Integer.valueOf(rounds))
                            .get(20L, TimeUnit.SECONDS));
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            }
        }, "small-stack-512k", 512L * 1024L);
        worker.start();
        worker.join(25000L);

        assertThat(worker.isAlive()).as("推进链路挂住（栈溢出被回调层吞掉）").isFalse();
        assertThat(failure.get()).isNull();
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(shared).isNotNull();
    }

    /** 轮次顺序与每轮一条记录：蹦床改造不能改变记录条数与顺序。 */
    @Test
    public void roundOrderAndRecordCountUnchanged() throws Exception {
        int rounds = 50;
        RecordingStore store = new RecordingStore();
        LlmGateway gateway = gateway(new AlwaysToolUseTransport(rounds), store,
                new InstantToolExecutor(), null);

        gateway.toolLoopAsync(call(), Collections.singletonList(echoTool()), rounds)
                .get(30L, TimeUnit.SECONDS);

        // 记录在 ai-lite-call-log-* 池上异步写入，等它们全部落地再断言。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (store.records.size() < rounds && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        // 前 rounds-1 轮各一条 TOOL_USE，最后一轮一条 STOP。
        // 记录里存的是 wire 值（toolUse / stop），不是枚举名。
        assertThat(store.records).hasSize(rounds);
        // 记录池有两个线程，写入顺序不等于轮次顺序：按轮号排序后再校验内容。
        // （只断言"每轮恰好一条、结局对得上"，不断言物理写入顺序。）
        List<Integer> roundNos = new ArrayList<Integer>();
        for (String record : store.records) {
            roundNos.add(Integer.valueOf(record.substring(record.indexOf('#') + 1)));
        }
        java.util.Collections.sort(roundNos);
        for (int i = 0; i < rounds; i++) {
            assertThat(roundNos.get(i))
                    .as("第 %d 轮的轮号应连续出现", i + 1)
                    .isEqualTo(i + 1);
        }
        // 结局：除最后一轮外都是 toolUse，最后一轮是 stop。
        int toolUse = 0;
        int stop = 0;
        for (String record : store.records) {
            if (record.startsWith("toolUse#")) {
                toolUse++;
            } else if (record.startsWith("stop#")) {
                stop++;
            }
        }
        assertThat(toolUse).as("前 %d 轮各一条 toolUse", rounds - 1).isEqualTo(rounds - 1);
        assertThat(stop).as("最后一轮一条 stop").isEqualTo(1);
    }

    /**
     * 蹦床循环中取消：不再发起下一轮。
     *
     * <p>第一次工具执行时取消外部句柄，之后 {@code next()} 入口的取消检查
     * 必须让循环立刻收敛为 ABORTED，而不是继续跑完 1000 轮。</p>
     */
    @Test
    public void cancelDuringDrainStopsImmediately() throws Exception {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        final AtomicInteger toolRuns = new AtomicInteger();
        ToolExecutor cancelOnFirstTool = new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                toolRuns.incrementAndGet();
                cancel.cancel();
                return Collections.singletonList("ok");
            }

            @Override
            public CompletableFuture<List<String>> executeAsync(List<LlmTool> tools,
                                                                List<LlmToolCall> calls) {
                return CompletableFuture.completedFuture(execute(tools, calls));
            }
        };
        AlwaysToolUseTransport transport = new AlwaysToolUseTransport(1000);
        LlmGateway gateway = gateway(transport, new NoopLlmCallStore(),
                cancelOnFirstTool, null);

        LlmResult result = gateway.toolLoopAsync(call(),
                Collections.singletonList(echoTool()), Integer.valueOf(1000), cancel)
                .get(30L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        // 取消发生在第一轮工具执行后：工具只跑了 1 次，后续轮次没有发起。
        assertThat(toolRuns.get()).isEqualTo(1);
        assertThat(transport.calls.get()).isEqualTo(1);
    }

    /**
     * 排队交接与同步完成混合时，不能出现两轮并发推进。
     *
     * <p>guard 的快速路径（调用线程同步 onGranted）与排队路径（载体线程
     * onGranted）都通向 {@code startRound}。两者同时到达时必须收编到同一
     * 推进入口，否则每轮会被推进两次，请求数与记录数都会翻倍。</p>
     */
    @Test
    public void guardQueuedPathDoesNotDoubleDrain() throws Exception {
        int rounds = 200;
        // 并发=1、等待 5s、队列 10：多轮之间串行，且会真的走排队交接路径。
        ProviderGuard guard = new ProviderGuard("test", 1, 5000L, 10, null,
                System::currentTimeMillis,
                (task, delayMs) -> scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS),
                null);
        AlwaysToolUseTransport transport = new AlwaysToolUseTransport(rounds);
        RecordingStore store = new RecordingStore();
        LlmGateway gateway = gateway(transport, store, new InstantToolExecutor(), guard);

        LlmResult result = gateway.toolLoopAsync(call(),
                Collections.singletonList(echoTool()), rounds).get(30L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        // 每轮恰好一次请求、一条记录——出现两轮并发推进就会翻倍。
        assertThat(transport.calls.get()).isEqualTo(rounds);
        // 记录异步写入：等它们全部落地再断言条数。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (store.records.size() < rounds && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.records).hasSize(rounds);
    }

    /** 真异步路径（未完成 future）不被蹦床改造影响。 */
    @Test
    public void asyncTransportStillCompletesNormally() throws Exception {
        final CountDownLatch released = new CountDownLatch(1);
        LlmTransport async = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new UnsupportedOperationException("async 用例不应走同步入口");
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        released.await(5L, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return new LlmCompletion("answer", null, LlmStopReason.STOP,
                            LlmUsage.zero(), null);
                });
            }
        };
        LlmGateway gateway = gateway(async, new NoopLlmCallStore(),
                new SequentialToolExecutor(), null);

        CompletableFuture<LlmResult> future = gateway.toolLoopAsync(call(),
                Collections.singletonList(echoTool()), 4);
        released.countDown();
        assertThat(future.get(10L, TimeUnit.SECONDS).getContent()).isEqualTo("answer");
    }
}

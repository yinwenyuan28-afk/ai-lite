package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-03 对抗性用例：取消必须真的中断在途 HTTP，而不只是把 future 改成 ABORTED。
 *
 * <p>模拟 OkHttpExecutor 的时序：future.complete 之后、finally 里才 unbind。
 * 网关在 complete 的同一条线程上内联发起下一轮并绑定新 abort，随后上一轮的
 * unbind(null) 会把它擦掉。批次 A 只补用例，不改产品代码。</p>
 */
public class RoundCancelRegressionTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));
    private static final long TIMEOUT_SECONDS = 3L;

    @Test
    public void secondRoundCancelAbortsInFlightHttp() throws Exception {
        final LateUnbindTransport transport = new LateUnbindTransport();
        LlmGateway gateway = gateway(transport);
        final LlmCancelHandle cancel = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway.toolLoopAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(echoTool()), Integer.valueOf(3), cancel);

        assertThat(transport.round1Started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 第 1 轮响应到达；网关内联发起第 2 轮并绑定 abort，然后才轮到上一轮解绑。
        transport.finishRound1WithLateUnbind();
        assertThat(transport.round2Started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("第 2 轮必须已发起")
                .isTrue();

        cancel.cancel();

        assertThat(transport.round2Aborts.get())
                .as("取消必须中断第 2 轮的底层连接")
                .isEqualTo(1);
        // 改前这里同样是 ABORTED —— 正是「看起来取消了，HTTP 还在跑」的现象。
        assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
    }

    @Test
    public void eachRoundUsesFreshCancelHandle() throws Exception {
        final MultiRoundTransport transport = new MultiRoundTransport(6);
        LlmGateway gateway = gateway(transport);

        gateway.toolLoopAsync(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(echoTool()), Integer.valueOf(6))
                .get(5L, TimeUnit.SECONDS);

        Set<LlmCancelHandle> distinct = new HashSet<LlmCancelHandle>(transport.handles);
        assertThat(distinct)
                .as("每次真实 HTTP 请求必须使用自己的一次性句柄")
                .hasSize(6);
    }

    // ---------------------------------------------------------------- 支撑件

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport),
                new io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore(),
                new DirectExecutorProvider(), inlineToolExecutor());
    }

    /** 工具 future 同步完成，让「上一轮响应 → 下一轮发起」完全内联，时序可复现。 */
    private static ToolExecutor inlineToolExecutor() {
        return new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                return Collections.singletonList("ok");
            }

            @Override
            public CompletableFuture<List<String>> executeAsync(List<LlmTool> tools,
                                                                List<LlmToolCall> calls) {
                return CompletableFuture.completedFuture(
                        Collections.singletonList("ok"));
            }
        };
    }

    private static final class LateUnbindTransport implements LlmTransport {

        private final AtomicReference<CompletableFuture<LlmCompletion>> round1Future =
                new AtomicReference<CompletableFuture<LlmCompletion>>();
        private final AtomicReference<LlmCancelHandle> round1Handle =
                new AtomicReference<LlmCancelHandle>();
        private final AtomicInteger round2Aborts = new AtomicInteger();
        private final AtomicInteger roundCount = new AtomicInteger();
        final CountDownLatch round1Started = new CountDownLatch(1);
        final CountDownLatch round2Started = new CountDownLatch(1);

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException("async only");
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            int round = roundCount.incrementAndGet();
            if (round == 1) {
                CompletableFuture<LlmCompletion> pending = new CompletableFuture<LlmCompletion>();
                request.getCancel().bindAbort(new Runnable() {
                    @Override
                    public void run() {
                        // 第 1 轮的 abort；这里不需要产生可观察效果。
                    }
                });
                round1Future.set(pending);
                round1Handle.set(request.getCancel());
                round1Started.countDown();
                return pending;
            }
            request.getCancel().bindAbort(new Runnable() {
                @Override
                public void run() {
                    round2Aborts.incrementAndGet();
                }
            });
            round2Started.countDown();
            // 第 2 轮永远不给响应：只有取消能中断它。
            return new CompletableFuture<LlmCompletion>();
        }

        void finishRound1WithLateUnbind() {
            round1Future.get().complete(toolUse());
            // OkHttpExecutor.java:175 —— complete 在 finally 的 unbind 之前。
            round1Handle.get().bindAbort(null);
        }
    }

    private static final class MultiRoundTransport implements LlmTransport {

        private final int rounds;
        private final AtomicInteger count = new AtomicInteger();
        final List<LlmCancelHandle> handles =
                Collections.synchronizedList(new ArrayList<LlmCancelHandle>());

        MultiRoundTransport(int rounds) {
            this.rounds = rounds;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException("async only");
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            handles.add(request.getCancel());
            int round = count.incrementAndGet();
            LlmStopReason stop = round >= rounds ? LlmStopReason.STOP : LlmStopReason.TOOL_USE;
            List<LlmToolCall> calls = stop == LlmStopReason.TOOL_USE
                    ? Collections.singletonList(new LlmToolCall("c" + round, "echo", "{}"))
                    : null;
            return CompletableFuture.completedFuture(
                    new LlmCompletion(stop == LlmStopReason.STOP ? "done" : "",
                            null, stop, LlmUsage.zero(), calls));
        }
    }

    private static LlmCompletion toolUse() {
        return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(new LlmToolCall("c1", "echo", "{}")));
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
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "ok";
            }
        };
    }
}

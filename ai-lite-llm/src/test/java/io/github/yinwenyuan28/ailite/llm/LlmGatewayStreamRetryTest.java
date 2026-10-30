package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * oneByStreamWithRetry：可重试窗口只到首个增量为止。
 *
 * <p>核心契约两条：首增量前失败按瞬时错误重试；一旦交付过增量，即使失败本身
 * 属于可重试类别也必须立刻停止，绝不重发（否则会产生重复输出）。</p>
 */
public class LlmGatewayStreamRetryTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService caller = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        caller.shutdownNow();
    }

    @Test
    public void retriesBeforeFirstDeltaThenSucceeds() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .succeedWithDeltas("ok", "第一段", "第二段");
        List<String> received = new ArrayList<String>();

        LlmResult result = gateway(transport)
                .oneByStreamWithRetry(call(), collect(received), fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
        // 失败的尝试没有交付任何增量，用户只看到成功那一次的完整内容。
        assertThat(received).containsExactly("第一段", "第二段");
    }

    @Test
    public void doesNotRetryAfterFirstDeltaEvenForRetryableError() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "已经吐出的内容");
        List<String> received = new ArrayList<String>();
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.oneByStreamWithRetry(
                call(), collect(received), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class);

        // 只发了一次：重发会让用户看到重复输出。
        assertThat(transport.requests).hasSize(1);
        // 已交付的增量保留，不被回滚（库无法撤回，只能如实保留）。
        assertThat(received).containsExactly("已经吐出的内容");
    }

    @Test
    public void doesNotRetryAfterFirstDeltaAcrossManyAttempts() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "x");

        assertThatThrownBy(() -> gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(5)))
                .isInstanceOf(LlmCallException.class);

        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void abandonedFailureKeepsRetryableClassificationWhenNoDeltaDelivered() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(httpError(503))
                .succeedWithDeltas("ok", "done");

        LlmResult result = gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(2));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void nonRetryableErrorBeforeFirstDeltaStopsImmediately() {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(400));

        assertThatThrownBy(() -> gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(((LlmCallException) ex).getStatusCode())
                        .isEqualTo(Integer.valueOf(400)));

        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void exhaustedAttemptsBeforeFirstDeltaKeepSuppressedFailures() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).fail(transportError()).fail(transportError());

        assertThatThrownBy(() -> gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(2));

        assertThat(transport.requests).hasSize(3);
    }

    @Test
    public void cancelDuringBackoffStopsFurtherAttempts() throws Exception {
        final ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        final LlmCancelHandle cancel = new LlmCancelHandle();
        final LlmGateway gateway = gateway(transport);
        RetryPolicy slow = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(1000L).maxBackoffMs(1000L)
                .jitter(false).build();

        Future<LlmResult> future = caller.submit(new java.util.concurrent.Callable<LlmResult>() {
            @Override
            public LlmResult call() {
                return gateway.oneByStreamWithRetry(
                        LlmGatewayStreamRetryTest.call(),
                        collect(new ArrayList<String>()), slow, cancel);
            }
        });
        assertThat(transport.firstRequest.await(5, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();

        LlmResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void preCancelSendsNoRequest() {
        ScriptedTransport transport = new ScriptedTransport().succeedWithDeltas("ok", "x");
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        LlmResult result = gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(3), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void totalDeadlineStopsBeforeAnotherAttempt() {
        // 持续失败：无论 sleep 是否满打满算地越过 deadline，最终都只能收敛到
        // deadlineFailure，不会因为假传输「耗尽后退化为成功」而漏判。
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).fail(transportError()).fail(transportError())
                .fail(transportError()).fail(transportError());
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5).initialBackoffMs(1000L).maxBackoffMs(1000L)
                .jitter(false).totalDeadlineMs(30L).build();

        assertThatThrownBy(() -> gateway(transport)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), policy))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("重试超过总时限");
        // 退避被裁剪到总时限内，绝不可能跑满 5 次。
        assertThat(transport.requests.size()).isBetween(1, 2);
    }

    @Test
    public void eachAttemptWritesItsOwnRecord() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .succeedWithDeltas("ok", "done");
        RecordingStore store = new RecordingStore();

        gateway(transport, store)
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), fastPolicy(3));

        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.ERROR.wire());
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.STOP.wire());
    }

    @Test
    public void retryAfterHeaderIsHonoredBeforeFirstDelta() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(new LlmCallException("http 429", null, LlmCallException.Kind.HTTP,
                        Integer.valueOf(429), Long.valueOf(60L)))
                .succeedWithDeltas("ok", "done");

        long start = System.nanoTime();
        gateway(transport).oneByStreamWithRetry(call(), collect(new ArrayList<String>()),
                RetryPolicy.builder().maxAttempts(2).initialBackoffMs(0L).maxBackoffMs(200L)
                        .jitter(false).build());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 指数退避是 0ms，只有采纳 Retry-After 才会真的等上几十毫秒。
        assertThat(elapsedMs).isGreaterThanOrEqualTo(40L);
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void nullOnDeltaStillRetriesAndReturnsResult() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .succeedWithDeltas("ok", "x");

        LlmResult result = gateway(transport)
                .oneByStreamWithRetry(call(), null, fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void nullPolicyIsRejected() {
        assertThatThrownBy(() -> gateway(new ScriptedTransport())
                .oneByStreamWithRetry(call(), collect(new ArrayList<String>()), null))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(((LlmCallException) ex).getKind())
                        .isEqualTo(LlmCallException.Kind.CONFIG));
    }

    @Test
    public void alwaysModeRetriesAfterDeliveryAndNotifiesDiscard() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "第一段")
                .succeedWithDeltas("ok", "第二段");
        List<String> received = new ArrayList<String>();
        final int[] discarded = new int[1];

        LlmResult result = gateway(transport).oneByStreamWithRetry(
                call(), collect(received), fastPolicy(3), null, StreamRetryMode.ALWAYS,
                new Runnable() {
                    @Override
                    public void run() {
                        discarded[0]++;
                        // 模拟前端清掉本轮已展示内容。
                        received.clear();
                    }
                });

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
        // 清理回调在重发前触发一次，最终只剩成功尝试的增量。
        assertThat(discarded[0]).isEqualTo(1);
        assertThat(received).containsExactly("第二段");
    }

    @Test
    public void alwaysModeWithoutDeliveredDeltaDoesNotNotifyDiscard() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .succeedWithDeltas("ok", "第一段");
        final int[] discarded = new int[1];

        gateway(transport).oneByStreamWithRetry(call(), collect(new ArrayList<String>()),
                fastPolicy(3), null, StreamRetryMode.ALWAYS, new Runnable() {
                    @Override
                    public void run() {
                        discarded[0]++;
                    }
                });

        // 那次失败的尝试没交付任何增量，没有东西需要清理。
        assertThat(discarded[0]).isZero();
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void defaultModeNeverRetriesAfterDeliveryEvenWhenModeIsNull() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "已经吐出的内容");

        assertThatThrownBy(() -> gateway(transport).oneByStreamWithRetry(
                call(), collect(new ArrayList<String>()), fastPolicy(3), null, null, null))
                .isInstanceOf(LlmCallException.class);

        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void alwaysModeStillStopsOnNonRetryableError() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(httpError(400), "第一段");
        final int[] discarded = new int[1];

        assertThatThrownBy(() -> gateway(transport).oneByStreamWithRetry(
                call(), collect(new ArrayList<String>()), fastPolicy(3), null,
                StreamRetryMode.ALWAYS, new Runnable() {
                    @Override
                    public void run() {
                        discarded[0]++;
                    }
                }))
                .isInstanceOf(LlmCallException.class);

        // 400 不可重试，ALWAYS 只放宽「已交付」这道闸门，不改变错误分类。
        assertThat(transport.requests).hasSize(1);
        assertThat(discarded[0]).isZero();
    }

    @Test
    public void alwaysModeDiscardCallbackFailureDoesNotBreakRetry() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "第一段")
                .succeedWithDeltas("ok", "第二段");

        LlmResult result = gateway(transport).oneByStreamWithRetry(
                call(), collect(new ArrayList<String>()), fastPolicy(3), null,
                StreamRetryMode.ALWAYS, new Runnable() {
                    @Override
                    public void run() {
                        throw new IllegalStateException("cleanup exploded");
                    }
                });

        // 清理回调抛异常只记录日志，重试照常进行。
        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void alwaysModeRespectsMaxAttempts() {
        ScriptedTransport transport = new ScriptedTransport()
                .failAfterDeltas(transportError(), "a")
                .failAfterDeltas(transportError(), "b")
                .failAfterDeltas(transportError(), "c");

        assertThatThrownBy(() -> gateway(transport).oneByStreamWithRetry(
                call(), collect(new ArrayList<String>()), fastPolicy(2), null,
                StreamRetryMode.ALWAYS, null))
                .isInstanceOf(LlmCallException.class);

        assertThat(transport.requests).hasSize(2);
    }

    private static java.util.function.Consumer<String> collect(final List<String> sink) {
        return new java.util.function.Consumer<String>() {
            @Override
            public void accept(String delta) {
                sink.add(delta);
            }
        };
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return gateway(transport, new NoopLlmCallStore());
    }

    private static LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static RetryPolicy fastPolicy(int attempts) {
        return RetryPolicy.builder()
                .maxAttempts(attempts)
                .initialBackoffMs(1L)
                .maxBackoffMs(1L)
                .jitter(false)
                .build();
    }

    private static LlmCallException transportError() {
        return new LlmCallException("boom", new IOException("io"),
                LlmCallException.Kind.TRANSPORT);
    }

    private static LlmCallException httpError(int status) {
        return new LlmCallException("http " + status, null,
                LlmCallException.Kind.HTTP, Integer.valueOf(status));
    }

    /**
     * 支持三种剧本：直接失败、先吐若干增量再失败、吐若干增量后成功。
     */
    private static final class ScriptedTransport implements LlmTransport {

        private final Deque<Script> outcomes = new ArrayDeque<Script>();
        final List<LlmRequest> requests =
                Collections.synchronizedList(new ArrayList<LlmRequest>());
        final java.util.concurrent.CountDownLatch firstRequest =
                new java.util.concurrent.CountDownLatch(1);

        ScriptedTransport fail(LlmCallException failure) {
            synchronized (outcomes) {
                outcomes.addLast(new Script(failure, null, Collections.<String>emptyList()));
            }
            return this;
        }

        ScriptedTransport succeedWithDeltas(String content, String... deltas) {
            List<String> list = new ArrayList<String>();
            for (String delta : deltas) {
                list.add(delta);
            }
            synchronized (outcomes) {
                outcomes.addLast(new Script(null, content, list));
            }
            return this;
        }

        /** 先交付增量，再抛错：模拟「中途断流」。 */
        ScriptedTransport failAfterDeltas(LlmCallException failure, String... deltas) {
            List<String> list = new ArrayList<String>();
            for (String delta : deltas) {
                list.add(delta);
            }
            synchronized (outcomes) {
                outcomes.addLast(new Script(failure, null, list));
            }
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            firstRequest.countDown();
            Script script;
            synchronized (outcomes) {
                script = outcomes.isEmpty()
                        ? new Script(null, "", Collections.<String>emptyList())
                        : outcomes.removeFirst();
            }
            java.util.function.Consumer<String> onDelta = request.getOnDelta();
            if (onDelta != null) {
                for (String delta : script.deltas) {
                    onDelta.accept(delta);
                }
            }
            if (script.failure != null) {
                throw script.failure;
            }
            return new LlmCompletion(script.content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }

    private static final class Script {
        final LlmCallException failure;
        final String content;
        final List<String> deltas;

        Script(LlmCallException failure, String content, List<String> deltas) {
            this.failure = failure;
            this.content = content;
            this.deltas = deltas;
        }
    }

    private static final class RecordingStore implements LlmCallStore {
        final List<LlmCallRecord> records =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record);
        }
    }
}

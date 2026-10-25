package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步续聊：加载、循环、落盘的 future 链路与取消语义。
 */
public class AgentSessionLoopAsyncTest {

    @Test
    public void resumeAsyncPersistsAssistantReply() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.of(1, 2, 3), null));
        AgentSessionLoop sessionLoop = sessionLoop(transport);
        sessionLoop.ensureSession("u", "s", "biz");

        AgentSessionLoop.ResumeResult result = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hi")))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getResult().getContent()).isEqualTo("answer");
        assertThat(result.getSession().getMessages()).hasSize(2);
        assertThat(result.getSession().getMessages().get(0).getText()).isEqualTo("hi");
        assertThat(result.getSession().getMessages().get(1).getText()).isEqualTo("answer");
    }

    @Test
    public void cancelledAsyncResumeDoesNotPersist() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        AgentSessionLoop sessionLoop = sessionLoop(transport);
        sessionLoop.ensureSession("u", "s", "biz");
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        AgentSessionLoop.ResumeResult result = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), cancel,
                Collections.singletonList(AgentSessionMessage.user("hi")))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getResult().getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getSession().getMessages()).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void missingSessionCompletesExceptionally() {
        AgentSessionLoop sessionLoop = sessionLoop(new GatewayTestSupport.FakeTransport());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                sessionLoop.resumeAsync("u", "missing",
                        LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                        null, null, Integer.valueOf(4), null, null)
                        .get(5, TimeUnit.SECONDS)))
                .hasMessageContaining("会话不存在");
    }

    @Test
    public void cancellingReturnedFutureAbortsInFlightTransport() throws Exception {
        final AtomicBoolean aborted = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch abortCalled = new CountDownLatch(1);
        LlmTransport never = new LlmTransport() {
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
                started.countDown();
                request.getCancel().bindAbort(new Runnable() {
                    @Override
                    public void run() {
                        aborted.set(true);
                        abortCalled.countDown();
                    }
                });
                return new CompletableFuture<LlmCompletion>();
            }
        };
        AgentSessionLoop sessionLoop = sessionLoop(never);
        sessionLoop.ensureSession("u", "s", "biz");

        CompletableFuture<AgentSessionLoop.ResumeResult> future = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hi")));

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(future.cancel(true)).isTrue();
        assertThat(abortCalled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(aborted).isTrue();
    }

    @Test
    public void prepareAndFinishRunOnProvidedExecutor() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.zero(), null));
        final AgentSessionStore delegate = new InMemoryAgentSessionStore(10, 60);
        final String[] saveThread = new String[1];
        AgentSessionStore recording = new AgentSessionStore() {
            @Override
            public Optional<AgentSession> find(String userId, String sessionId) {
                return delegate.find(userId, sessionId);
            }

            @Override
            public void save(AgentSession session) {
                saveThread[0] = Thread.currentThread().getName();
                delegate.save(session);
            }

            @Override
            public void delete(String userId, String sessionId) {
                delegate.delete(userId, sessionId);
            }

            @Override
            public boolean exists(String userId, String sessionId) {
                return delegate.exists(userId, sessionId);
            }
        };
        AiLiteConfig.Executor config = new AiLiteConfig.Executor();
        config.setCoreSize(1);
        config.setMaxSize(1);
        config.setThreadNamePrefix("session-io-");
        PlatformExecutorProvider executor = new PlatformExecutorProvider(config);
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        AgentSessionLoop sessionLoop = new AgentSessionLoop(new AgentLoop(gateway), recording, executor);
        try {
            sessionLoop.ensureSession("u", "s", "biz");
            saveThread[0] = null;
            sessionLoop.resumeAsync("u", "s",
                    LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                    null, null, Integer.valueOf(4), null,
                    Collections.singletonList(AgentSessionMessage.user("hi")))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.close();
        }

        assertThat(saveThread[0]).startsWith("session-io-");
    }

    @Test
    public void cancelBetweenLoopAndFinishDoesNotPersist() throws Exception {
        // SL-08/A5：取消落在"run 已完成、finish（落盘）排队未执行"的窗口——
        // 内层链被取消后 finish 不再执行，会话不落盘、future 保持 cancelled。
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.zero(), null));
        final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        final List<Runnable> queue = new CopyOnWriteArrayList<Runnable>();
        // 同步完成的 transport：run 的 whenComplete 内联在 compose 任务里，
        // 使 finish（thenApplyAsync）紧随 compose 入队，形成"已排队未执行"窗口。
        LlmTransport instant = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return new LlmCompletion("answer", null, LlmStopReason.STOP, LlmUsage.zero(), null);
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.completedFuture(complete(request));
            }
        };
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(instant), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        AgentSessionLoop sessionLoop = new AgentSessionLoop(new AgentLoop(gateway),
                store, new io.github.yinwenyuan28.ailite.executor.ExecutorProvider() {
            @Override
            public java.util.concurrent.Executor io() {
                return queue::add;
            }

            @Override
            public void close() {
            }
        });
        sessionLoop.ensureSession("u", "s", "biz");

        CompletableFuture<AgentSessionLoop.ResumeResult> future = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hi")));

        // 手动执行前两个 io 任务：1=prepare、2=compose（内层链创建，finish 随之入队）。
        for (int i = 0; i < 2 && !queue.isEmpty(); i++) {
            queue.remove(0).run();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(queue.size()).isEqualTo(1); // 仅剩 finish 排队未执行
        // 取消落在 finish 执行前：内层链被取消，finish 不再执行、不落盘。
        assertThat(future.cancel(true)).isTrue();
        while (!queue.isEmpty()) {
            queue.remove(0).run(); // 已取消的任务执行时应跳过 finish
        }

        // 会话停留在 ensureSession 创建时的空会话（version=0），没有落盘 assistant 回复。
        Optional<AgentSession> stored = store.find("u", "s");
        assertThat(stored).isPresent();
        assertThat(stored.get().getMessages()).isEmpty();
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    public void cancelAfterNormalCompletionReturnsFalse() throws Exception {
        // SL-08：正常完成后 future.cancel 返回 false，结果与会话不受影响。
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.zero(), null));
        AgentSessionLoop sessionLoop = sessionLoop(transport);
        sessionLoop.ensureSession("u", "s", "biz");

        CompletableFuture<AgentSessionLoop.ResumeResult> future = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hi")));
        AgentSessionLoop.ResumeResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(future.cancel(true)).isFalse();
        assertThat(result.getResult().getContent()).isEqualTo("answer");
        assertThat(result.getSession().getMessages()).hasSize(2);
    }

    @Test
    public void concurrentResumesNeverLoseUpdatesSilently() throws Exception {
        // SL-18 简版：并发 resumeAsync 同一会话——版本乐观锁保证至少一个成功、
        // 失败以异常显式呈现，不允许静默丢更新。
        final GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("a1", null, LlmStopReason.STOP, LlmUsage.zero(), null))
                .enqueue(new LlmCompletion("a2", null, LlmStopReason.STOP, LlmUsage.zero(), null));
        final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        final AgentSessionLoop sessionLoop = new AgentSessionLoop(new AgentLoop(gateway), store);
        sessionLoop.ensureSession("u", "s", "biz");
        final CountDownLatch start = new CountDownLatch(1);

        final List<CompletableFuture<AgentSessionLoop.ResumeResult>> futures =
                new CopyOnWriteArrayList<CompletableFuture<AgentSessionLoop.ResumeResult>>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        CompletableFuture<AgentSessionLoop.ResumeResult> f = sessionLoop.resumeAsync("u", "s",
                                LlmCall.profile("biz", "main",
                                        Collections.singletonList(LlmMessage.user("hi" + idx))),
                                null, null, Integer.valueOf(4), null,
                                Collections.singletonList(AgentSessionMessage.user("hi" + idx)));
                        futures.add(f);
                    } catch (Throwable ex) {
                        System.out.println("[DIAG] worker " + idx + " sync throw: " + ex);
                    }
                }
            });
            worker.start();
        }
        start.countDown();
        // 等两个 worker 都拿到 future 再判竞争结果（异步链完成耗时不定，轮询而非固定睡眠）。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (futures.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(futures).hasSize(2);

        int succeeded = 0;
        List<String> failures = new CopyOnWriteArrayList<String>();
        for (CompletableFuture<AgentSessionLoop.ResumeResult> f : futures) {
            try {
                f.get(5, TimeUnit.SECONDS);
                succeeded++;
            } catch (Exception expected) {
                // 失败以异常显式呈现（版本冲突或 transport 竞争），不允许静默丢更新。
                failures.add(String.valueOf(expected.getCause() == null
                        ? expected.getMessage() : expected.getCause().toString()));
            }
        }
        assertThat(succeeded).isGreaterThanOrEqualTo(1);
        assertThat(failures.size()).isLessThanOrEqualTo(1);
        Optional<AgentSession> stored = store.find("u", "s");
        assertThat(stored).isPresent();
        assertThat(stored.get().getMessages().size() % 2).isZero(); // user+assistant 成对
    }

    private static AgentSessionLoop sessionLoop(LlmTransport transport) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        return new AgentSessionLoop(new AgentLoop(gateway),
                new InMemoryAgentSessionStore(10, 60));
    }
}

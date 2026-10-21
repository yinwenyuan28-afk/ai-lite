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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    private static AgentSessionLoop sessionLoop(LlmTransport transport) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        return new AgentSessionLoop(new AgentLoop(gateway),
                new InMemoryAgentSessionStore(10, 60));
    }
}

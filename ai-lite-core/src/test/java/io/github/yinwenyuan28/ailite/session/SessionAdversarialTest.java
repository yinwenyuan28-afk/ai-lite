package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话编解码、存储和续聊循环的对抗性用例。
 *
 * <p>重点：损坏 JSON、重复/缺失 id、版本冲突、TTL/容量淘汰、取消不落盘、
 * 保存失败不留半截、缓存故障降级。</p>
 */
public class SessionAdversarialTest {

    // --------------------------------------------------------------- codec

    @Test
    public void blankJsonDecodesToEmptyMessages() {
        assertThat(AgentSessionCodec.decodeMessages(null)).isEmpty();
        assertThat(AgentSessionCodec.decodeMessages("   ")).isEmpty();
    }

    @Test
    public void corruptOrNonArrayMessageJsonIsRejected() {
        assertThatThrownBy(() -> AgentSessionCodec.decodeMessages("{not-json"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("无法解析");
        assertThatThrownBy(() -> AgentSessionCodec.decodeMessages("{\"id\":\"m\"}"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    public void messageWithoutRoleIsRejected() {
        assertThatThrownBy(() -> AgentSessionCodec.decodeMessages(
                "[{\"type\":\"message\",\"id\":\"m1\",\"text\":\"x\"}]"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 role");
    }

    @Test
    public void illegalOrLowercaseRoleIsRejected() {
        assertThatThrownBy(() -> AgentSessionCodec.decodeMessages(
                "[{\"type\":\"message\",\"id\":\"m1\",\"role\":\"BOT\",\"text\":\"x\"}]"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("role 非法");
        assertThatThrownBy(() -> AgentSessionCodec.decodeMessages(
                "[{\"type\":\"message\",\"id\":\"m1\",\"role\":\"user\",\"text\":\"x\"}]"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("role 非法");
    }

    @Test
    public void messageWithoutIdDecodesButStoreRejectsIt() {
        List<AgentSessionMessage> decoded = AgentSessionCodec.decodeMessages(
                "[{\"type\":\"message\",\"role\":\"USER\",\"text\":\"x\"}]");
        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).getId()).isNull();

        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSession session = session("u", "s", 0L, decoded);
        assertThatThrownBy(() -> store.save(session))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 id");
    }

    @Test
    public void duplicateIdsAreRejectedByStoreDespiteCodecAcceptingThem() {
        List<AgentSessionMessage> decoded = AgentSessionCodec.decodeMessages(
                "[{\"type\":\"message\",\"id\":\"same\",\"role\":\"USER\",\"text\":\"a\"},"
                        + "{\"type\":\"message\",\"id\":\"same\",\"role\":\"USER\",\"text\":\"b\"}]");
        assertThat(decoded).hasSize(2);

        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        assertThatThrownBy(() -> store.save(session("u", "s", 0L, decoded)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    public void nonArrayMessagesInSessionShouldNotBeSilentlyDropped() {
        String json = "{\"userId\":\"u\",\"sessionId\":\"s\",\"version\":1,"
                + "\"messages\":{\"id\":\"m1\",\"role\":\"USER\",\"text\":\"lost\"}}";

        // 期望：拒绝损坏的会话，避免静默丢历史。实际：返回 messages=[]。
        assertThatThrownBy(() -> AgentSessionCodec.decodeSession(json))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    public void nonObjectSessionJsonReturnsNullForAbsentOrWrongRoot() {
        assertThat(AgentSessionCodec.decodeSession(null)).isNull();
        assertThat(AgentSessionCodec.decodeSession("[]")).isNull();
        assertThat(AgentSessionCodec.decodeSession("null")).isNull();
        assertThatThrownBy(() -> AgentSessionCodec.decodeSession("{oops"))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    public void updatedAtHandlesMissingStringZeroAndNegativeValues() {
        AgentSession missing = AgentSessionCodec.decodeSession(
                "{\"userId\":\"u\",\"sessionId\":\"s\",\"messages\":[]}");
        assertThat(missing.getUpdatedAt()).isNull();

        AgentSession nonNumeric = AgentSessionCodec.decodeSession(
                "{\"userId\":\"u\",\"sessionId\":\"s\",\"updatedAt\":\"yesterday\",\"messages\":[]}");
        assertThat(nonNumeric.getUpdatedAt()).isNull();

        AgentSession numericString = AgentSessionCodec.decodeSession(
                "{\"userId\":\"u\",\"sessionId\":\"s\",\"updatedAt\":\"1760940000000\",\"messages\":[]}");
        assertThat(numericString.getUpdatedAt()).isNotNull();
        assertThat(numericString.getUpdatedAt().getTime()).isEqualTo(1760940000000L);

        AgentSession zero = AgentSessionCodec.decodeSession(
                "{\"userId\":\"u\",\"sessionId\":\"s\",\"updatedAt\":0,\"messages\":[]}");
        assertThat(zero.getUpdatedAt()).isNull();

        AgentSession negative = AgentSessionCodec.decodeSession(
                "{\"userId\":\"u\",\"sessionId\":\"s\",\"updatedAt\":-5,\"messages\":[]}");
        assertThat(negative.getUpdatedAt()).isNull();
    }

    @Test
    public void encodeRejectsNullSessionAndNullMessage() {
        assertThatThrownBy(() -> AgentSessionCodec.encodeSession(null))
                .isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> AgentSessionCodec.encodeMessages(
                Arrays.asList(AgentSessionMessage.user("ok"), null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("会话消息不能为空");
    }

    @Test
    public void messagesToLlmSkipsBrokenEntriesAndMapsOtherToUser() {
        List<AgentSessionMessage> source = Arrays.asList(
                null,
                AgentSessionMessage.of("m1", null, "no-role", null, null, null),
                AgentSessionMessage.of("m2", LlmRole.OTHER, "other", null, null, null),
                AgentSessionMessage.of("m3", LlmRole.ASSISTANT, "answer", "think", null, null));

        List<LlmMessage> llm = AgentSession.messagesToLlm(source, false);

        assertThat(llm).hasSize(2);
        // 会话层保留 OTHER，出站 fold 时才转成 user。
        assertThat(llm.get(0).getRole()).isEqualTo(LlmRole.OTHER);
        assertThat(llm.get(1).getRole()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(llm.get(1).getText()).isEqualTo("answer");
    }

    // ------------------------------------------------------------- message

    @Test
    public void fromResultPeelsThinkingOnlyWhenContentStartsWithIt() {
        AgentSessionMessage spliced = AgentSessionMessage.fromResult(
                "think", "think\n\nanswer", null);
        assertThat(spliced.getText()).isEqualTo("answer");
        assertThat(spliced.getThinkingText()).isEqualTo("think");

        AgentSessionMessage identical = AgentSessionMessage.fromResult(
                "think", "think", null);
        assertThat(identical.getText()).isEmpty();
        assertThat(identical.getThinkingText()).isEqualTo("think");

        AgentSessionMessage unrelated = AgentSessionMessage.fromResult(
                "think", "answer", null);
        assertThat(unrelated.getText()).isEqualTo("answer");

        AgentSessionMessage blankThinking = AgentSessionMessage.fromResult(
                "   ", "answer", null);
        assertThat(blankThinking.getThinkingText()).isNull();
        assertThat(blankThinking.getText()).isEqualTo("answer");

        AgentSessionMessage nullThinking = AgentSessionMessage.fromResult(
                null, "answer", null);
        assertThat(nullThinking.getThinkingText()).isNull();
        assertThat(nullThinking.getText()).isEqualTo("answer");
    }

    // --------------------------------------------------------------- store

    @Test
    public void newSessionWithNonZeroVersionIsRejected() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);

        assertThatThrownBy(() -> store.save(session("u", "s", 3L,
                Collections.<AgentSessionMessage>emptyList())))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    public void storeRejectsNullSessionAndBlankKeys() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);

        assertThatThrownBy(() -> store.save(null)).isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> store.find(" ", "s")).isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> store.find("u", "")).isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> store.delete(null, "s")).isInstanceOf(LlmCallException.class);
    }

    @Test
    public void findReturnsAnIsolatedCopy() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L,
                Collections.singletonList(AgentSessionMessage.user("original"))));

        AgentSession loaded = store.find("u", "s").get();
        loaded.getMessages().add(AgentSessionMessage.user("injected"));
        loaded.setBizId("mutated");

        AgentSession again = store.find("u", "s").get();
        assertThat(again.getMessages()).hasSize(1);
        assertThat(again.getMessages().get(0).getText()).isEqualTo("original");
        assertThat(again.getBizId()).isEqualTo("biz");
    }

    @Test
    public void ttlExpiresStoredSession() throws Exception {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 1);
        store.save(session("u", "s", 0L, Collections.<AgentSessionMessage>emptyList()));

        assertThat(store.exists("u", "s")).isTrue();
        Thread.sleep(1100L);
        assertThat(store.exists("u", "s")).isFalse();
    }

    @Test
    public void zeroTtlIsCoercedToSevenDaysInsteadOfExpiringImmediately() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 0);
        store.save(session("u", "s", 0L, Collections.<AgentSessionMessage>emptyList()));

        assertThat(store.exists("u", "s")).isTrue();
    }

    @Test
    public void maxEntriesEvictionKeepsTheNewestSessions() throws Exception {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(2, 60);
        for (int i = 0; i < 3; i++) {
            store.save(session("u", "s" + i, 0L,
                    Collections.singletonList(AgentSessionMessage.user("m" + i))));
            // Windows 的文件时间/系统时间粒度较粗，留足 20ms 保证 updatedAt 可排序。
            Thread.sleep(20L);
        }

        assertThat(store.exists("u", "s0")).isFalse();
        assertThat(store.exists("u", "s1")).isTrue();
        assertThat(store.exists("u", "s2")).isTrue();
    }

    @Test
    public void concurrentSavesWithTheSameVersionAllowExactlyOneWinner() throws Exception {
        final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L, Collections.<AgentSessionMessage>emptyList()));
        final AgentSession loaded = store.find("u", "s").get();

        final int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        store.save(AgentSession.copyOf(loaded));
                        successes.incrementAndGet();
                    } catch (LlmCallException ex) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(store.find("u", "s").get().getVersion()).isEqualTo(2L);
    }

    // ------------------------------------------------------------- caching

    @Test
    public void cacheGetFailureFallsBackToDurableStore() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        durable.save(session("u", "s", 0L,
                Collections.singletonList(AgentSessionMessage.user("persisted"))));
        FlakyCache cache = new FlakyCache();
        cache.failGet = true;
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60);

        AgentSession loaded = store.find("u", "s").get();

        assertThat(loaded.getMessages()).hasSize(1);
        assertThat(cache.failGet).isTrue();
    }

    @Test
    public void cachePutAndDeleteFailureDoNotFailTheSave() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        FlakyCache cache = new FlakyCache();
        cache.failPut = true;
        cache.failDelete = true;
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60);

        store.save(session("u", "s", 0L,
                Collections.singletonList(AgentSessionMessage.user("hello"))));

        assertThat(durable.find("u", "s").isPresent()).isTrue();
        assertThat(durable.find("u", "s").get().getMessages()).hasSize(1);
    }

    @Test
    public void cacheDeleteFailureDoesNotFailDelete() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        durable.save(session("u", "s", 0L, Collections.<AgentSessionMessage>emptyList()));
        FlakyCache cache = new FlakyCache();
        cache.failDelete = true;
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60);

        store.delete("u", "s");

        assertThat(durable.exists("u", "s")).isFalse();
    }

    // ---------------------------------------------------------- session loop

    @Test
    public void abortedTurnIsNotPersisted() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("never", null, LlmStopReason.STOP, LlmUsage.zero(), null));
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = loop(transport, store);
        loop.ensureSession("u", "s", "biz");

        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        AgentSessionLoop.ResumeResult result = loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), cancel,
                Collections.singletonList(AgentSessionMessage.user("not-saved")));

        assertThat(result.getResult().getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getSession().getVersion()).isEqualTo(1L);
        AgentSession persisted = store.find("u", "s").get();
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getMessages()).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void saveFailureLeavesPreviousSessionIntact() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP, LlmUsage.zero(), null));
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        FailingStore failing = new FailingStore(durable);
        AgentSessionLoop loop = loop(transport, failing);
        loop.ensureSession("u", "s", "biz");
        failing.failNextSave = true;

        assertThatThrownBy(() -> loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), null,
                Collections.singletonList(AgentSessionMessage.user("hello"))))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("save failed");

        AgentSession persisted = durable.find("u", "s").get();
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getMessages()).isEmpty();
    }

    @Test
    public void duplicateUserTurnIdIsRejectedBeforeCallingTheModel() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = loop(transport, store);
        loop.ensureSession("u", "s", "biz");

        AgentSessionMessage duplicated = AgentSessionMessage.user("dup");
        assertThatThrownBy(() -> loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), null,
                Arrays.asList(duplicated, duplicated)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("id 重复");
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void secondTurnReceivesHistoryWithoutThinkingSplice() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("think\n\nfirst-answer", "think",
                        LlmStopReason.STOP, LlmUsage.zero(), null))
                .enqueue(new LlmCompletion("second-answer", null,
                        LlmStopReason.STOP, LlmUsage.zero(), null));
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = loop(transport, store);
        loop.ensureSession("u", "s", "biz");

        loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), null,
                Collections.singletonList(AgentSessionMessage.user("hello")));
        AgentSessionLoop.ResumeResult second = loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), null,
                Collections.singletonList(AgentSessionMessage.user("again")));

        assertThat(second.getSession().getMessages()).hasSize(4);
        assertThat(second.getSession().getMessages().get(1).getText()).isEqualTo("first-answer");
        assertThat(second.getSession().getMessages().get(1).getThinkingText()).isEqualTo("think");

        List<LlmMessage> sent = transport.requests().get(1).getMessages();
        LlmMessage assistant = null;
        for (LlmMessage message : sent) {
            if (message.getRole() == LlmRole.ASSISTANT) {
                assistant = message;
            }
        }
        assertThat(assistant).isNotNull();
        // 续聊默认不把 thinking 拼回正文。
        assertThat(assistant.getText()).isEqualTo("first-answer");
    }

    @Test
    public void resumeOnMissingSessionFailsWithoutSaving() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = loop(transport, store);

        assertThatThrownBy(() -> loop.resume("u", "missing",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(2), null,
                Collections.singletonList(AgentSessionMessage.user("hi"))))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("会话不存在");
        assertThat(store.exists("u", "missing")).isFalse();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void ensureSessionIsIdempotent() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = loop(new GatewayTestSupport.FakeTransport(), store);

        AgentSession first = loop.ensureSession("u", "s", "biz");
        AgentSession second = loop.ensureSession("u", "s", "other-biz");

        assertThat(second.getVersion()).isEqualTo(first.getVersion());
        assertThat(second.getBizId()).isEqualTo("biz");
    }

    // -------------------------------------------------------------- helpers

    private static AgentSessionLoop loop(io.github.yinwenyuan28.ailite.llm.LlmTransport transport,
                                         AgentSessionStore store) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        return new AgentSessionLoop(new AgentLoop(gateway), store);
    }

    private static AgentSession session(String userId, String sessionId, long version,
                                        List<AgentSessionMessage> messages) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setVersion(version);
        session.setMessages(messages);
        return session;
    }

    /**
     * 可注入单次保存失败的包装存储。
     */
    static final class FailingStore implements AgentSessionStore {
        private final AgentSessionStore delegate;
        boolean failNextSave;

        FailingStore(AgentSessionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<AgentSession> find(String userId, String sessionId) {
            return delegate.find(userId, sessionId);
        }

        @Override
        public void save(AgentSession session) {
            if (failNextSave) {
                failNextSave = false;
                throw new LlmCallException("save failed");
            }
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
    }

    /**
     * 可逐项注入故障的内存缓存。
     */
    static final class FlakyCache implements SessionCache {
        boolean failGet;
        boolean failPut;
        boolean failDelete;

        @Override
        public Optional<AgentSession> get(String userId, String sessionId) {
            if (failGet) {
                throw new IllegalStateException("cache get down");
            }
            return Optional.empty();
        }

        @Override
        public void put(AgentSession session, long ttlSeconds) {
            if (failPut) {
                throw new IllegalStateException("cache put down");
            }
        }

        @Override
        public void delete(String userId, String sessionId) {
            if (failDelete) {
                throw new IllegalStateException("cache delete down");
            }
        }
    }
}

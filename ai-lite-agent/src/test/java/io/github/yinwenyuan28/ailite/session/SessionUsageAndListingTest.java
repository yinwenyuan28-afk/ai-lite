package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话用量记账与列举。
 *
 * <p>口径（用户决策 Q4）：取消/失败轮不计入会话累计，账单表另记。
 * 因为取消轮在 {@code finish} 之前就返回了，压根不会产生 {@link UsageEntry}。</p>
 */
public class SessionUsageAndListingTest {

    // ------------------------------------------------------------ 用量记账

    @Test
    public void successfulRoundAppendsUsageEntry() {
        Harness harness = Harness.create();
        harness.enqueue(stop("a", LlmUsage.of(10, 5, 15)));

        AgentSessionLoop.ResumeResult result = harness.resume("hi");

        assertThat(usageEntries(result.getSession())).hasSize(1);
        UsageEntry usage = usageEntries(result.getSession()).get(0);
        assertThat(usage.getKind()).isEqualTo(UsageEntry.KIND_CHAT);
        assertThat(usage.getInputTokens()).isEqualTo(10);
        assertThat(usage.getOutputTokens()).isEqualTo(5);
        assertThat(usage.getTotalTokens()).isEqualTo(15);
        assertThat(result.getSession().getTotalTokens()).isEqualTo(15L);
    }

    @Test
    public void multipleRoundsAccumulateTotalTokens() {
        Harness harness = Harness.create();
        harness.enqueue(stop("a", LlmUsage.of(10, 5, 15)));
        harness.enqueue(stop("b", LlmUsage.of(20, 10, 30)));

        harness.resume("first");
        AgentSessionLoop.ResumeResult second = harness.resume("second");

        assertThat(usageEntries(second.getSession())).hasSize(2);
        assertThat(second.getSession().getTotalTokens()).isEqualTo(45L);
    }

    @Test
    public void cancelledRoundDoesNotAddUsage() {
        Harness harness = Harness.create();
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        AgentSessionLoop.ResumeResult result = harness.loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), cancel,
                Collections.singletonList(AgentSessionMessage.user("hi")));

        assertThat(result.getResult().getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(usageEntries(result.getSession())).isEmpty();
        assertThat(result.getSession().getTotalTokens()).isZero();
    }

    @Test
    public void zeroUsageRoundProducesNoEntry() {
        Harness harness = Harness.create();
        harness.enqueue(stop("a", LlmUsage.zero()));

        AgentSessionLoop.ResumeResult result = harness.resume("hi");

        // 供应商没返回 usage 时不写空条目，避免会话里堆无用记录。
        assertThat(usageEntries(result.getSession())).isEmpty();
        assertThat(result.getSession().getTotalTokens()).isZero();
    }

    /**
     * 用量条目必须能经存储往返（否则重启后累计就丢了）。
     */
    @Test
    public void usageEntrySurvivesStoreRoundTrip() {
        Harness harness = Harness.create();
        harness.enqueue(stop("a", LlmUsage.of(10, 5, 15)));
        harness.resume("hi");

        AgentSession reloaded = harness.store.find("u", "s").get();

        assertThat(usageEntries(reloaded)).hasSize(1);
        assertThat(reloaded.getTotalTokens()).isEqualTo(15L);
    }

    // ------------------------------------------------------------ 会话列举

    @Test
    public void listByUserReturnsOnlyOwnSessions() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(50, 600);
        saveSession(store, "u1", "s1", "标题一", 100L);
        saveSession(store, "u1", "s2", "标题二", 200L);
        saveSession(store, "u2", "s3", "别人的", 300L);

        List<AgentSessionSummary> listed = store.listByUser("u1", 0, 10);

        assertThat(listed).hasSize(2);
        assertThat(listed).extracting("sessionId").containsExactly("s2", "s1");
    }

    @Test
    public void listByUserPaginatesStably() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(50, 600);
        // 三条会话更新时间完全相同，靠 sessionId 倒序保证稳定。
        for (int i = 1; i <= 3; i++) {
            saveSession(store, "u", "s" + i, "标题" + i, 1000L);
        }

        List<AgentSessionSummary> firstPage = store.listByUser("u", 0, 2);
        List<AgentSessionSummary> secondPage = store.listByUser("u", 2, 2);

        assertThat(firstPage).extracting("sessionId").containsExactly("s3", "s2");
        assertThat(secondPage).extracting("sessionId").containsExactly("s1");
        // 两页无重叠、无遗漏。
        assertThat(firstPage.size() + secondPage.size()).isEqualTo(3);
    }

    @Test
    public void listByUserBeyondEndReturnsEmpty() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(50, 600);
        saveSession(store, "u", "s1", "标题", 100L);

        assertThat(store.listByUser("u", 10, 5)).isEmpty();
    }

    @Test
    public void summaryCarriesCountsAndTitle() {
        Harness harness = Harness.create();
        harness.enqueue(stop("answer", LlmUsage.of(10, 5, 15)));
        harness.resume("请帮我筛选简历");

        List<AgentSessionSummary> listed = harness.store.listByUser("u", 0, 10);

        assertThat(listed).hasSize(1);
        AgentSessionSummary summary = listed.get(0);
        assertThat(summary.getTitle()).isEqualTo("请帮我筛选简历");
        assertThat(summary.getMessageCount()).isEqualTo(2);
        assertThat(summary.getTotalTokens()).isEqualTo(15L);
    }

    @Test
    public void supportsListingIsTrueForInMemoryStore() {
        assertThat(new InMemoryAgentSessionStore(10, 60).supportsListing()).isTrue();
    }

    /**
     * 自定义存储不实现列举时应能启动，只是不支持——不能因为 default 返回空而报错。
     */
    @Test
    public void storeWithoutListingSupportReturnsEmptyNotThrows() {
        AgentSessionStore minimal = new AgentSessionStore() {
            @Override
            public java.util.Optional<AgentSession> find(String userId, String sessionId) {
                return java.util.Optional.empty();
            }

            @Override
            public void save(AgentSession session) {
            }

            @Override
            public void delete(String userId, String sessionId) {
            }

            @Override
            public boolean exists(String userId, String sessionId) {
                return false;
            }
        };

        assertThat(minimal.supportsListing()).isFalse();
        assertThat(minimal.listByUser("u", 0, 10)).isEmpty();
    }

    /**
     * 带缓存的存储必须显式委托列举，否则"列不出任何会话"。
     */
    @Test
    public void cachingStoreDelegatesListing() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(50, 600);
        saveSession(durable, "u", "s1", "标题", 100L);
        CachingAgentSessionStore caching = new CachingAgentSessionStore(durable,
                new InMemorySessionCache(10), 60);

        assertThat(caching.supportsListing()).isTrue();
        assertThat(caching.listByUser("u", 0, 10)).hasSize(1);
    }

    // ------------------------------------------------------------- 夹具

    private static LlmCompletion stop(String content, LlmUsage usage) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, usage, null);
    }

    private static List<UsageEntry> usageEntries(AgentSession session) {
        List<UsageEntry> entries = new ArrayList<UsageEntry>();
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry instanceof UsageEntry) {
                entries.add((UsageEntry) entry);
            }
        }
        return entries;
    }

    private static void saveSession(AgentSessionStore store, String userId, String sessionId,
                                    String title, long totalTokens) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setTitle(title);
        session.setTotalTokens(totalTokens);
        session.setVersion(0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));
        store.save(session);
    }

    private static final class Harness {

        private final GatewayTestSupport.FakeTransport transport;
        private final InMemoryAgentSessionStore store;
        private final AgentSessionLoop loop;

        private Harness(GatewayTestSupport.FakeTransport transport,
                        InMemoryAgentSessionStore store, AgentSessionLoop loop) {
            this.transport = transport;
            this.store = store;
            this.loop = loop;
        }

        static Harness create() {
            GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(50, 600);
            AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway), store);
            loop.ensureSession("u", "s", "biz");
            return new Harness(transport, store, loop);
        }

        void enqueue(LlmCompletion completion) {
            transport.enqueue(completion);
        }

        AgentSessionLoop.ResumeResult resume(String userText) {
            return loop.resume("u", "s",
                    LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                    Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                    Collections.singletonList(AgentSessionMessage.user(userText)));
        }
    }
}

package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-G 持久化往返硬门禁（RT1–RT7）。
 *
 * <p><b>为什么单独设门禁</b>：压缩条目一旦在任一次保存链路上被静默丢掉，
 * 压缩就变成"每轮算摘要、每轮花钱、每轮被丢弃"，而且不会报错。
 * 单测如果只验证内存对象，是测不出这个缺陷的。因此这里全部走真实的
 * 存储实现与真实的续聊链路，逐条证明条目能完整往返。</p>
 *
 * <p><b>这些测试覆盖的三个历史缺陷入口</b>：</p>
 * <ol>
 *   <li>{@code AgentSessionStoreSupport} 三个 default 方法只写消息（RT6）</li>
 *   <li>{@code AgentSessionCodec.encodeSession} 只序列化消息（RT3）</li>
 *   <li>{@code AgentSessionLoop.finish} 用 {@code new AgentSession()} 重建对象（RT5）</li>
 * </ol>
 *
 * <p>RT2（真实 JDBC）见 {@code ai-lite-session-jdbc} 模块的
 * {@code JdbcSessionEntryRoundTripTest}。</p>
 */
public class SessionEntryRoundTripGateTest {

    private static final String SUMMARY = "用户先问了天气，又确认了日程；已同意改用 24 小时制。";

    // ------------------------------------------------------------------ RT1

    /**
     * RT1：内存存储完整往返。compaction 条目必须仍在，且**位置不变**。
     */
    @Test
    public void rt1InMemoryStoreKeepsCompactionEntryInPlace() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSession session = sessionWithCompaction();
        List<String> expectedOrder = entryIds(session);

        store.save(session);
        AgentSession loaded = store.find("u", "s").orElseThrow(
                () -> new AssertionError("会话未保存"));

        assertThat(entryIds(loaded)).isEqualTo(expectedOrder);
        CompactionEntry compaction = onlyCompaction(loaded);
        assertThat(compaction.getSummary()).isEqualTo(SUMMARY);
        assertThat(compaction.getFirstKeptEntryId()).isEqualTo("m3");
        assertThat(compaction.getTokensBefore()).isEqualTo(28000);
        assertThat(compaction.getSummaryModel()).isEqualTo("deepseek/deepseek-chat");
        assertThat(compaction.getSummaryTokens()).isEqualTo(180);
    }

    // ------------------------------------------------------------------ RT3

    /**
     * RT3：会话 JSON 编解码往返。这是 Redis 缓存的实际路径。
     */
    @Test
    public void rt3CodecRoundTripKeepsCompactionEntry() {
        AgentSession session = sessionWithCompaction();
        List<String> expectedOrder = entryIds(session);

        String json = AgentSessionCodec.encodeSession(session);
        AgentSession decoded = AgentSessionCodec.decodeSession(json);

        assertThat(entryIds(decoded)).isEqualTo(expectedOrder);
        assertThat(onlyCompaction(decoded).getSummary()).isEqualTo(SUMMARY);

        // 独立验证：编码结果里确实写了 type，且四种已注册类型都可读。
        assertThat(json).contains("\"type\":\"compaction\"");
        assertThat(json).contains("\"type\":\"message\"");
        assertThat(AgentSessionCodec.registeredTypes())
                .containsExactlyInAnyOrder("message", "compaction", "session_info", "usage");
    }

    /**
     * RT3 补充：session_info 与 usage 条目也能往返，且不混进消息视图。
     */
    @Test
    public void rt3CodecRoundTripKeepsAllEntryTypes() {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
        entries.add(AgentSessionMessage.of("m1", LlmRole.SYSTEM, "你是助手", null, null, null));
        entries.add(AgentSessionMessage.of("m2", LlmRole.USER, "hi", null, null, null));
        entries.add(new SessionInfoEntry("i1", "第一次会话"));
        entries.add(new UsageEntry("g1", UsageEntry.KIND_CHAT, "test", "m", 10, 5, 15));
        session.setEntries(entries);

        AgentSession decoded = AgentSessionCodec.decodeSession(
                AgentSessionCodec.encodeSession(session));

        assertThat(entryIds(decoded)).containsExactly("m1", "m2", "i1", "g1");
        assertThat(decoded.getMessages()).hasSize(2);
        assertThat(decoded.nonMessageEntryCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ RT4

    /**
     * RT4：带缓存的存储。save 之后从缓存读到的会话必须仍含 compaction 条目。
     */
    @Test
    public void rt4CachingStoreKeepsCompactionEntryThroughCache() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        InMemorySessionCache cache = new InMemorySessionCache(10);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60);
        AgentSession session = sessionWithCompaction();
        List<String> expectedOrder = entryIds(session);

        store.save(session);
        // 第一次 find 走缓存命中（save 已回写）；第二次 find 走真实读库。
        AgentSession fromCache = store.find("u", "s").orElseThrow(
                () -> new AssertionError("会话未保存"));
        assertThat(entryIds(fromCache)).isEqualTo(expectedOrder);
        assertThat(onlyCompaction(fromCache).getSummary()).isEqualTo(SUMMARY);

        cache.delete("u", "s");
        AgentSession fromStore = store.find("u", "s").orElseThrow(
                () -> new AssertionError("会话未保存"));
        assertThat(entryIds(fromStore)).isEqualTo(expectedOrder);
        assertThat(onlyCompaction(fromStore).getSummary()).isEqualTo(SUMMARY);
    }

    // ------------------------------------------------------------------ RT5

    /**
     * RT5（最关键）：压缩后走一轮真实 {@code resume} 到 {@code finish}，再从 store 读回。
     *
     * <p>这条覆盖的是最致命的缺陷入口：{@code finish} 曾用 {@code new AgentSession()}
     * 重建对象，只带走消息。因为 {@code finish} 是每轮续聊的必经路径，
     * 这个缺陷会让压缩条目在每一次续聊后被丢弃。</p>
     */
    @Test
    public void rt5FullResumeFinishPathKeepsCompactionEntry() {
        Harness harness = Harness.create();
        harness.seed(sessionWithCompaction());
        List<String> beforeOrder = entryIds(harness.store.find("u", "s").get());

        AgentSessionLoop.ResumeResult result = harness.loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<io.github.yinwenyuan28.ailite.llm.LlmTool>emptyList(),
                null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("再来一轮")));

        assertThat(result.getResult().getContent()).isEqualTo("answer");

        AgentSession stored = harness.store.find("u", "s").orElseThrow(
                () -> new AssertionError("会话未保存"));
        CompactionEntry compaction = onlyCompaction(stored);
        assertThat(compaction.getSummary()).isEqualTo(SUMMARY);
        assertThat(compaction.getFirstKeptEntryId()).isEqualTo("m3");
        assertThat(compaction.getTokensBefore()).isEqualTo(28000);

        // 压缩条目仍在原来的相对位置：前缀区之后、m3 之前。
        List<String> afterOrder = entryIds(stored);
        assertThat(afterOrder.indexOf("c1")).isEqualTo(beforeOrder.indexOf("c1"));
        // 本轮新增的 user + assistant 追加在末尾，消息总数 +2。
        assertThat(stored.getMessages()).hasSize(sessionWithCompaction().getMessages().size() + 2);
    }

    /**
     * RT5 补充：连续两轮续聊后，压缩条目数量不增不减。
     */
    @Test
    public void rt5RepeatedResumesNeverDropOrDuplicateCompactionEntry() {
        Harness harness = Harness.create();
        harness.seed(sessionWithCompaction());

        for (int i = 0; i < 3; i++) {
            harness.loop.resume("u", "s",
                    LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                    Collections.<io.github.yinwenyuan28.ailite.llm.LlmTool>emptyList(),
                    null, Integer.valueOf(4), null,
                    Collections.singletonList(AgentSessionMessage.user("第 " + i + " 轮")));
        }

        AgentSession stored = harness.store.find("u", "s").get();
        int compactions = 0;
        for (AgentSessionEntry entry : stored.getEntries()) {
            if (entry instanceof CompactionEntry) {
                compactions++;
            }
        }
        assertThat(compactions).isEqualTo(1);
        assertThat(onlyCompaction(stored).getSummary()).isEqualTo(SUMMARY);
    }

    // ------------------------------------------------------------------ RT6

    /**
     * RT6：三个消息级 default 方法都不得丢掉 compaction 条目。
     */
    @Test
    public void rt6MessageLevelCrudKeepsCompactionEntryInPlace() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(sessionWithCompaction());
        int compactionIndex = entryIds(store.find("u", "s").get()).indexOf("c1");

        // appendMessage
        AgentSessionMessage appended = AgentSessionMessage.user("追加");
        store.appendMessage("u", "s", appended);
        AgentSession afterAppend = store.find("u", "s").get();
        assertThat(afterAppend.getEntries()).anyMatch(entry -> entry instanceof CompactionEntry);
        assertThat(entryIds(afterAppend).indexOf("c1")).isEqualTo(compactionIndex);
        assertThat(onlyCompaction(afterAppend).getSummary()).isEqualTo(SUMMARY);

        // replaceMessage
        store.replaceMessage("u", "s", AgentSessionMessage.of("m1", LlmRole.SYSTEM, "改过的系统提示",
                null, null, null));
        AgentSession afterReplace = store.find("u", "s").get();
        assertThat(onlyCompaction(afterReplace).getSummary()).isEqualTo(SUMMARY);
        assertThat(entryIds(afterReplace).indexOf("c1")).isEqualTo(compactionIndex);

        // deleteMessage（删中间的消息，压缩条目位置随之前移一位但仍在 m3 之前）
        store.deleteMessage("u", "s", "m2");
        AgentSession afterDelete = store.find("u", "s").get();
        assertThat(onlyCompaction(afterDelete).getSummary()).isEqualTo(SUMMARY);
        List<String> ids = entryIds(afterDelete);
        assertThat(ids).doesNotContain("m2");
        assertThat(ids.indexOf("c1")).isLessThan(ids.indexOf("m3"));
    }

    /**
     * RT6 补充：删除到只剩压缩条目时也不能丢它。
     */
    @Test
    public void rt6DeletingAllMessagesKeepsCompactionEntry() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(sessionWithCompaction());

        for (String id : new String[]{"m1", "m2", "m3", "m4"}) {
            store.deleteMessage("u", "s", id);
        }

        AgentSession stored = store.find("u", "s").get();
        assertThat(stored.getMessages()).isEmpty();
        assertThat(onlyCompaction(stored).getSummary()).isEqualTo(SUMMARY);
    }

    // ------------------------------------------------------------------ RT7

    /**
     * RT7：前缀区不被破坏。经任一往返后，数组开头仍必须是连续 SYSTEM 条目。
     */
    @Test
    public void rt7PrefixRegionSurvivesAllRoundTrips() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSession session = sessionWithCompaction();
        store.save(session);

        assertPrefixRegionIntact(store.find("u", "s").get());

        AgentSession decoded = AgentSessionCodec.decodeSession(
                AgentSessionCodec.encodeSession(store.find("u", "s").get()));
        assertPrefixRegionIntact(decoded);

        store.appendMessage("u", "s", AgentSessionMessage.user("追加"));
        assertPrefixRegionIntact(store.find("u", "s").get());
    }

    /**
     * RT7 补充：合并消息时新消息数量少于旧消息，前缀区仍须完好。
     */
    @Test
    public void rt7PrefixRegionSurvivesMessageShrink() {
        AgentSession session = sessionWithCompaction();
        // 只保留前缀两条与压缩条目，其余消息全部"删除"。
        session.setMessages(Collections.singletonList(
                AgentSessionMessage.of("m1", LlmRole.SYSTEM, "你是助手", null, null, null)));

        List<AgentSessionEntry> entries = session.getEntries();
        assertThat(entries.get(0)).isInstanceOf(AgentSessionMessage.class);
        assertThat(((AgentSessionMessage) entries.get(0)).getRole()).isEqualTo(LlmRole.SYSTEM);
        // 第二条前缀消息被删除后，压缩条目不应跑到最前面造成"前缀区被压缩条目截断"的误判。
        assertThat(entries.get(entries.size() - 1)).isInstanceOf(CompactionEntry.class);
    }

    // ------------------------------------------------------------- 公共夹具

    /**
     * 构造含前缀区 + 一条 compaction 的会话：
     * {@code [system m1][system m2][compaction c1][user m3][assistant m4]}。
     */
    static AgentSession sessionWithCompaction() {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        session.setBizId("biz");
        session.setVersion(0L);
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
        entries.add(AgentSessionMessage.of("m1", LlmRole.SYSTEM, "你是助手", null, null, null));
        entries.add(AgentSessionMessage.of("m2", LlmRole.SYSTEM, "输出中文", null, null, null));
        entries.add(new CompactionEntry("c1", SUMMARY, "m3", 28000,
                "deepseek/deepseek-chat", 180));
        entries.add(AgentSessionMessage.of("m3", LlmRole.USER, "帮我看看这份简历", null, null, null));
        entries.add(AgentSessionMessage.of("m4", LlmRole.ASSISTANT, "好的", null, null, null));
        session.setEntries(entries);
        return session;
    }

    private static List<String> entryIds(AgentSession session) {
        List<String> ids = new ArrayList<String>();
        for (AgentSessionEntry entry : session.getEntries()) {
            ids.add(entry.getId());
        }
        return ids;
    }

    private static CompactionEntry onlyCompaction(AgentSession session) {
        CompactionEntry found = null;
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry instanceof CompactionEntry) {
                assertThat(found).as("会话里出现了多条压缩条目").isNull();
                found = (CompactionEntry) entry;
            }
        }
        assertThat(found).as("会话里没有压缩条目").isNotNull();
        return found;
    }

    private static void assertPrefixRegionIntact(AgentSession session) {
        List<AgentSessionEntry> entries = session.getEntries();
        assertThat(entries).isNotEmpty();
        for (int i = 0; i < 2; i++) {
            assertThat(entries.get(i)).isInstanceOf(AgentSessionMessage.class);
            assertThat(((AgentSessionMessage) entries.get(i)).getRole())
                    .as("第 %d 条应属于前缀区", Integer.valueOf(i))
                    .isEqualTo(LlmRole.SYSTEM);
        }
    }

    /**
     * 真实续聊链路夹具：内存存储 + 假传输，按需返回固定回答。
     */
    private static final class Harness {

        private final AgentSessionLoop loop;
        private final AgentSessionStore store;

        private Harness(AgentSessionLoop loop, AgentSessionStore store) {
            this.loop = loop;
            this.store = store;
        }

        static Harness create() {
            GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
            for (int i = 0; i < 8; i++) {
                transport.enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.of(10, 5, 15), null));
            }
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            AgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
            AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway), store);
            loop.ensureSession("u", "s", "biz");
            return new Harness(loop, store);
        }

        /**
         * 把带压缩条目的会话写入存储（模拟压缩刚落盘）。
         *
         * @param session 会话
         */
        void seed(AgentSession session) {
            session.setVersion(store.find("u", "s").get().getVersion());
            store.save(session);
        }
    }
}

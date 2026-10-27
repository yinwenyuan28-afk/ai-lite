package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.internal.session.ContextBudget;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文预算、投影与压缩的验收测试。
 *
 * <p>核心断言：</p>
 * <ol>
 *   <li>超限且压缩关闭 → 抛 {@code CONTEXT_OVERFLOW}，错误消息含估算值与上限</li>
 *   <li>窗口未知 → 完全跳过检查</li>
 *   <li>压缩一次即收敛，下一轮不再触发</li>
 *   <li>压缩不删原始条目；关掉开关立刻恢复完整历史</li>
 *   <li>前缀区永不参与压缩</li>
 * </ol>
 */
public class ContextBudgetAndCompactionTest {

    private static final int WINDOW = 32768;
    private static final int RESERVE = 8192;
    private static final int TRIGGER = WINDOW - RESERVE;

    // ------------------------------------------------- 配置不变式（启动期）

    @Test
    public void contradictoryThresholdsFailFast() {
        // keep(24000) + summary(2048) = 26048 >= trigger(24576)：压缩后会立刻再次超限。
        AiLiteConfig.Context context = context(true, 24000, 2048);

        assertThatThrownBy(() -> new ContextBudget(context).validate(Integer.valueOf(WINDOW), "m"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("keep-recent-tokens(24000)")
                .hasMessageContaining("reserve-tokens(8192)")
                .hasMessageContaining("每轮重复压缩");
    }

    @Test
    public void healthyThresholdsPassValidation() {
        // 默认值：12000 + 2048 = 14048 < 24576。
        AiLiteConfig.Context context = context(true, 12000, 2048);

        new ContextBudget(context).validate(Integer.valueOf(WINDOW), "m");
    }

    @Test
    public void unknownWindowSkipsValidation() {
        AiLiteConfig.Context context = context(true, 20000, 2048);

        // 窗口未知时阈值矛盾无从判断，不该阻止启动。
        new ContextBudget(context).validate(null, "m");
        new ContextBudget(context).validate(Integer.valueOf(0), "m");
    }

    @Test
    public void windowSmallerThanReserveMeansUnknown() {
        AiLiteConfig.Context context = context(true, 12000, 2048);
        ContextBudget budget = new ContextBudget(context);

        assertThat(budget.triggerTokens(4096)).isZero();
        assertThat(budget.triggerTokens(WINDOW)).isEqualTo(TRIGGER);
    }

    @Test
    public void maxPerRunBelowOneIsRejected() {
        AiLiteConfig.Context context = context(true, 12000, 2048);
        context.getCompaction().setMaxPerRun(0);

        assertThatThrownBy(() -> new ContextBudget(context).validate(null, "m"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("max-per-run");
    }

    // ------------------------------------------------------- 预算决策表

    @Test
    public void overLimitWithCompactionDisabledThrowsOverflow() {
        Harness harness = Harness.create(context(false, 12000, 2048));
        harness.seedLongHistory(40000);

        assertThatThrownBy(() -> harness.resume(AgentSessionMessage.user("新一轮")))
                .isInstanceOf(LlmCallException.class)
                .satisfies(error -> {
                    LlmCallException ex = (LlmCallException) error;
                    assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CONTEXT_OVERFLOW);
                    assertThat(ex.getMessage()).contains("估算");
                    assertThat(ex.getMessage()).contains(String.valueOf(TRIGGER));
                    assertThat(ex.getMessage()).contains("自动压缩已关闭");
                });
        assertThat(harness.transport.requests()).isEmpty();
    }

    @Test
    public void unknownWindowSkipsBudgetEntirely() {
        Harness harness = Harness.create(context(false, 12000, 2048), Integer.valueOf(0));
        harness.seedLongHistory(80000);

        // 窗口未知：不检查，正常发请求。
        harness.transport.enqueue(stop("ok"));
        AgentSessionLoop.ResumeResult result = harness.resume(AgentSessionMessage.user("hi"));

        assertThat(result.getResult().getContent()).isEqualTo("ok");
        assertThat(harness.transport.requests()).hasSize(1);
    }

    @Test
    public void underLimitDoesNotCompactOrThrow() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedLongHistory(2000);

        harness.transport.enqueue(stop("ok"));
        harness.resume(AgentSessionMessage.user("hi"));

        // 没有压缩调用（只有 1 次主调用）。
        assertThat(harness.transport.requests()).hasSize(1);
        assertThat(compactionCount(harness.session())).isZero();
    }

    // --------------------------------------------------------- 压缩行为

    @Test
    public void compactionTriggersOnceAndThenStops() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedLongHistory(40000);

        // 第一次：摘要调用 + 主调用
        harness.transport.enqueue(stop("summary-body"));
        harness.transport.enqueue(stop("answer-1"));
        AgentSessionLoop.ResumeResult first = harness.resume(AgentSessionMessage.user("第一轮"));
        assertThat(first.getResult().getContent()).isEqualTo("answer-1");
        assertThat(compactionCount(harness.session())).isEqualTo(1);
        assertThat(harness.transport.requests()).hasSize(2);

        // 第二次：只需主调用，摘要被复用（这就是"压缩只花一次钱"）。
        harness.transport.enqueue(stop("answer-2"));
        harness.resume(AgentSessionMessage.user("第二轮"));
        assertThat(compactionCount(harness.session())).isEqualTo(1);
        assertThat(harness.transport.requests()).hasSize(3);
    }

    @Test
    public void compactionKeepsAllOriginalMessages() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedLongHistory(40000);
        int messagesBefore = harness.session().getMessages().size();

        harness.transport.enqueue(stop("summary-body"));
        harness.transport.enqueue(stop("answer"));
        harness.resume(AgentSessionMessage.user("新一轮"));

        // 摘要 + 本轮 user + assistant 新增，但一条原始消息都没删。
        AgentSession session = harness.session();
        assertThat(session.getMessages().size()).isGreaterThanOrEqualTo(messagesBefore);
        assertThat(compactionCount(session)).isEqualTo(1);
    }

    @Test
    public void projectionStartsWithPrefixRegionThenSummary() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedPrefixAndLongHistory(40000);

        harness.transport.enqueue(stop("摘要正文"));
        harness.transport.enqueue(stop("answer"));
        harness.resume(AgentSessionMessage.user("新一轮"));

        // 先在投影层验证结构（投影是纯计算，未经过 LlmTexts.fold 合并）。
        List<AgentSessionEntry> projected = harness.loop.projectEntries(harness.session());
        assertThat(((AgentSessionMessage) projected.get(0)).getText()).isEqualTo("你是助手");
        assertThat(((AgentSessionMessage) projected.get(1)).getText()).isEqualTo("输出中文");
        assertThat(projected.get(2)).isInstanceOf(AgentSessionMessage.class);
        assertThat(((AgentSessionMessage) projected.get(2)).getRole()).isEqualTo(LlmRole.SYSTEM);
        assertThat(((AgentSessionMessage) projected.get(2)).getText()).isEqualTo("摘要正文");

        // 网关发送前会把所有 SYSTEM 用 "\n\n" 合并到最前，语义顺序应为「前缀在前、摘要在后」。
        List<LlmMessage> sent = harness.transport.requests().get(1).getMessages();
        assertThat(sent.get(0).getRole()).isEqualTo(LlmRole.SYSTEM);
        assertThat(sent.get(0).getText()).isEqualTo("你是助手\n\n输出中文\n\n摘要正文");
    }

    @Test
    public void disablingCompactionRestoresFullHistory() {
        // 同一个 store，两个 loop：一个压缩开启（已写入压缩条目），一个压缩关闭。
        Harness enabled = Harness.create(context(true, 12000, 2048));
        enabled.seedLongHistory(40000);

        enabled.transport.enqueue(stop("summary-body"));
        enabled.transport.enqueue(stop("answer"));
        enabled.resume(AgentSessionMessage.user("新一轮"));

        AgentSession session = enabled.session();
        int messagesInSession = session.getMessages().size();

        // 压缩开启时投影更短。
        int projectedWithCompaction = countMessages(enabled.loop.projectEntries(session));
        assertThat(projectedWithCompaction).isLessThan(messagesInSession);

        // 压缩关闭：同一份数据投影立刻回到完整历史，不需要改任何数据。
        AgentSessionLoop withoutCompaction = enabled.loopWith(context(false, 12000, 2048));
        int projectedWithout = countMessages(withoutCompaction.projectEntries(session));
        assertThat(projectedWithout).isEqualTo(messagesInSession);
    }

    @Test
    public void prefixRegionIsNeverSummarized() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedPrefixAndLongHistory(40000);

        harness.transport.enqueue(stop("摘要正文"));
        harness.transport.enqueue(stop("answer"));
        harness.resume(AgentSessionMessage.user("新一轮"));

        // 摘要调用的提示词里不能出现前缀区内容。
        List<LlmMessage> summaryPrompt = harness.transport.requests().get(0).getMessages();
        for (LlmMessage message : summaryPrompt) {
            assertThat(message.getText()).doesNotContain("你是助手");
            assertThat(message.getText()).doesNotContain("输出中文");
        }
    }

    @Test
    public void compactionEntrySurvivesResumeToFinish() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedLongHistory(40000);

        harness.transport.enqueue(stop("summary-body"));
        harness.transport.enqueue(stop("answer"));
        harness.resume(AgentSessionMessage.user("新一轮"));

        // 落盘后从 store 重新读，压缩条目仍在（RT5 的压缩场景版本）。
        AgentSession stored = harness.store.find("u", "s").orElseThrow(
                () -> new AssertionError("会话未保存"));
        assertThat(compactionCount(stored)).isEqualTo(1);
    }

    @Test
    public void summaryFailureThrowsOverflowNotTruncation() {
        Harness harness = Harness.create(context(true, 12000, 2048));
        harness.seedLongHistory(40000);

        // 摘要模型返回空内容 → 压缩失败 → 抛 CONTEXT_OVERFLOW。
        harness.transport.enqueue(stop(""));

        assertThatThrownBy(() -> harness.resume(AgentSessionMessage.user("新一轮")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("上下文超出模型窗口");
        // 失败时不写会话，也不留半截摘要。
        assertThat(compactionCount(harness.session())).isZero();
    }

    // ------------------------------------------------------------- 夹具

    private static AiLiteConfig.Context context(boolean compactionEnabled, int keep,
                                                int summaryMax) {
        AiLiteConfig.Context context = new AiLiteConfig.Context();
        context.setEnabled(true);
        context.getBudget().setReserveTokens(RESERVE);
        context.getCompaction().setEnabled(compactionEnabled);
        context.getCompaction().setKeepRecentTokens(keep);
        context.getCompaction().setSummaryMaxTokens(summaryMax);
        return context;
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static int compactionCount(AgentSession session) {
        int count = 0;
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry instanceof CompactionEntry) {
                count++;
            }
        }
        return count;
    }

    private static int countMessages(List<AgentSessionEntry> entries) {
        int count = 0;
        for (AgentSessionEntry entry : entries) {
            if (entry instanceof AgentSessionMessage) {
                count++;
            }
        }
        return count;
    }

    private static final class Harness {

        private final GatewayTestSupport.FakeTransport transport;
        private final InMemoryAgentSessionStore store;
        private final AgentLoop agentLoop;
        private final AgentSessionLoop loop;

        private Harness(GatewayTestSupport.FakeTransport transport,
                        InMemoryAgentSessionStore store, AgentLoop agentLoop, AgentSessionLoop loop) {
            this.transport = transport;
            this.store = store;
            this.agentLoop = agentLoop;
            this.loop = loop;
        }

        static Harness create(AiLiteConfig.Context context) {
            return create(context, Integer.valueOf(WINDOW));
        }

        static Harness create(AiLiteConfig.Context context, Integer contextWindow) {
            GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key", contextWindow),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
            AgentLoop agentLoop = new AgentLoop(gateway);
            AgentSessionLoop loop = new AgentSessionLoop(agentLoop, store, null, context);
            loop.ensureSession("u", "s", "biz");
            return new Harness(transport, store, agentLoop, loop);
        }

        AgentSessionLoop.ResumeResult resume(AgentSessionMessage turn) {
            return loop.resume("u", "s",
                    LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                    Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                    Collections.singletonList(turn));
        }

        AgentSession session() {
            return store.find("u", "s").orElseThrow(() -> new AssertionError("会话不存在"));
        }

        /**
         * 用同一会话数据建一个不同配置的 loop，用于验证配置切换不需要改数据。
         *
         * @param otherConfig 另一套上下文配置
         * @return 新 loop（共享同一 store 与网关）
         */
        AgentSessionLoop loopWith(AiLiteConfig.Context otherConfig) {
            return new AgentSessionLoop(agentLoop, store, null, otherConfig);
        }

        /** 写入够长的历史：每条 user/assistant 约 1000 token 量级。 */
        void seedLongHistory(int targetTokens) {
            AgentSession session = session();
            List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
            String body = repeat("中", 1600);
            int perMessage = 1000;
            int pairs = Math.max(1, targetTokens / (perMessage * 2));
            for (int i = 0; i < pairs; i++) {
                messages.add(AgentSessionMessage.user("第 " + i + " 轮：" + body));
                messages.add(AgentSessionMessage.assistant("回答 " + i + "：" + body, null, null));
            }
            session.setMessages(messages);
            store.save(session);
        }

        /** 历史前面加两条前缀 SYSTEM。 */
        void seedPrefixAndLongHistory(int targetTokens) {
            seedLongHistory(targetTokens);
            AgentSession session = session();
            List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
            entries.add(AgentSessionMessage.system("你是助手"));
            entries.add(AgentSessionMessage.system("输出中文"));
            entries.addAll(session.getEntries());
            session.setEntries(entries);
            store.save(session);
        }

        private static String repeat(String unit, int times) {
            StringBuilder builder = new StringBuilder(unit.length() * times);
            for (int i = 0; i < times; i++) {
                builder.append(unit);
            }
            return builder.toString();
        }
    }
}

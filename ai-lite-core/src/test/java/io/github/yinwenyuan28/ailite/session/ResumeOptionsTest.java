package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.AgentLifecycle;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ResumeOptions} 入口与旧 8 参重载的等价性守门。
 *
 * <p>这套测试是 T1 的验收门禁：功能只要挪到 options 上，就必须证明
 * "旧签名调用" 与 "显式全默认 options 调用" 在模型请求、落盘内容、
 * 最终返回值三个维度完全一致。任何差异都说明转调层引入了行为漂移。</p>
 */
public class ResumeOptionsTest {

    // ------------------------------------------------------- builder 语义

    @Test
    public void defaultsAreEmptyAndNullSafe() {
        ResumeOptions options = ResumeOptions.defaults();

        assertThat(options.getTools()).isEmpty();
        assertThat(options.getLifecycles()).isEmpty();
        assertThat(options.getNewUserTurns()).isEmpty();
        assertThat(options.getMaxRounds()).isNull();
        assertThat(options.getCancel()).isNull();
        assertThat(options.getTimeoutMs()).isNull();
        assertThat(options.getMaxToolResultChars()).isNull();
    }

    @Test
    public void listsAreDefensivelyCopiedAndImmutable() {
        List<LlmTool> tools = new ArrayList<LlmTool>();
        List<AgentSessionMessage> turns = new ArrayList<AgentSessionMessage>();
        turns.add(AgentSessionMessage.user("hi"));

        ResumeOptions options = ResumeOptions.builder().tools(tools).newUserTurns(turns).build();
        tools.add(null);
        turns.add(AgentSessionMessage.user("late"));

        assertThat(options.getTools()).isEmpty();
        assertThat(options.getNewUserTurns()).hasSize(1);
        assertThatThrownBy(() -> options.getTools().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void cancelHandleIsPassedThroughUnchanged() {
        LlmCancelHandle handle = new LlmCancelHandle();

        ResumeOptions options = ResumeOptions.builder().cancel(handle).build();

        assertThat(options.getCancel()).isSameAs(handle);
    }

    @Test
    public void invalidMaxRoundsIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> ResumeOptions.builder().maxRounds(Integer.valueOf(0)).build())
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("最大轮数");
    }

    @Test
    public void negativeToolResultLimitIsRejected() {
        assertThatThrownBy(() -> ResumeOptions.builder().maxToolResultChars(Integer.valueOf(-1)).build())
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不能为负数");
    }

    @Test
    public void zeroToolResultLimitMeansUnlimitedAndIsAccepted() {
        ResumeOptions options = ResumeOptions.builder().maxToolResultChars(Integer.valueOf(0)).build();

        assertThat(options.getMaxToolResultChars()).isEqualTo(Integer.valueOf(0));
    }

    // --------------------------------------------------- 等价性守门（核心）

    @Test
    public void legacySignatureEqualsOptionsWithDefaults() {
        // 两条完全相同的会话与传输，唯一差别是入口签名。
        Scenario legacy = Scenario.run(false);
        Scenario modern = Scenario.run(true);

        assertThat(modern.responses).isEqualTo(legacy.responses);
        assertThat(modern.requestRoles).isEqualTo(legacy.requestRoles);
        assertThat(modern.requestTexts).isEqualTo(legacy.requestTexts);
        assertThat(modern.storedRoles).isEqualTo(legacy.storedRoles);
        assertThat(modern.storedTexts).isEqualTo(legacy.storedTexts);
        assertThat(modern.sessionVersion).isEqualTo(legacy.sessionVersion);
        assertThat(modern.transportCalls).isEqualTo(legacy.transportCalls);
        assertThat(modern.hookCalls).isEqualTo(legacy.hookCalls);
    }

    @Test
    public void legacySignatureEqualsExplicitOptionsValues() {
        // 旧签名带具体值 vs options 带相同具体值。
        LegendValues values = LegendValues.standard();
        Scenario legacy = Scenario.runWithValues(false, values);
        Scenario modern = Scenario.runWithValues(true, values);

        assertThat(modern.responses).isEqualTo(legacy.responses);
        assertThat(modern.requestRoles).isEqualTo(legacy.requestRoles);
        assertThat(modern.storedRoles).isEqualTo(legacy.storedRoles);
        assertThat(modern.storedTexts).isEqualTo(legacy.storedTexts);
        assertThat(modern.sessionVersion).isEqualTo(legacy.sessionVersion);
        assertThat(modern.transportCalls).isEqualTo(legacy.transportCalls);
        assertThat(modern.hookCalls).isEqualTo(legacy.hookCalls);
    }

    @Test
    public void syncResumeRejectsTimeoutSilentlyIgnoredWouldBeWorse() {
        Scenario scenario = Scenario.create();
        LlmCancelHandle cancel = new LlmCancelHandle();

        assertThatThrownBy(() -> scenario.loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                ResumeOptions.builder().cancel(cancel).timeoutMs(Long.valueOf(1000L)).build()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("timeoutMs");
        assertThat(scenario.transport.requests()).isEmpty();
    }

    @Test
    public void nullOptionsIsRejected() {
        Scenario scenario = Scenario.create();

        assertThatThrownBy(() -> scenario.loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()), (ResumeOptions) null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不能为空");
    }

    // ------------------------------------------------------------ 测试夹具

    /**
     * 一次完整续聊的可观测结果快照，用于逐字段比较两条入口是否等价。
     */
    private static final class Scenario {

        private final AgentSessionLoop loop;
        private final AgentSessionStore store;
        private final GatewayTestSupport.FakeTransport transport;
        private final CountingHook hook;
        private List<String> responses = new ArrayList<String>();
        private List<String> requestRoles = new ArrayList<String>();
        private List<String> requestTexts = new ArrayList<String>();
        private List<String> storedRoles = new ArrayList<String>();
        private List<String> storedTexts = new ArrayList<String>();
        private int sessionVersion;
        private int transportCalls;
        private int hookCalls;

        private Scenario(AgentSessionLoop loop, AgentSessionStore store,
                         GatewayTestSupport.FakeTransport transport, CountingHook hook) {
            this.loop = loop;
            this.store = store;
            this.transport = transport;
            this.hook = hook;
        }

        static Scenario create() {
            GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                    .enqueue(new LlmCompletion("first", null, LlmStopReason.STOP,
                            LlmUsage.of(10, 5, 15), null))
                    .enqueue(new LlmCompletion("second", null, LlmStopReason.STOP,
                            LlmUsage.of(12, 6, 18), null));
            CountingHook hook = new CountingHook();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            AgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
            AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway), store);
            loop.ensureSession("u", "s", "biz");
            return new Scenario(loop, store, transport, hook);
        }

        /**
         * @param viaOptions true 走 options 入口，false 走旧 8 参入口
         */
        static Scenario run(boolean viaOptions) {
            LegendValues values = LegendValues.defaults();
            return runWithValues(viaOptions, values);
        }

        static Scenario runWithValues(boolean viaOptions, LegendValues values) {
            Scenario scenario = create();
            for (int round = 0; round < 2; round++) {
                AgentSessionMessage turn = AgentSessionMessage.user("turn-" + round);
                AgentSessionLoop.ResumeResult result = viaOptions
                        ? scenario.loop.resume("u", "s", values.call,
                                ResumeOptions.builder()
                                        .tools(values.tools)
                                        .lifecycles(Collections.<AgentLifecycle>singletonList(
                                                scenario.hook))
                                        .maxRounds(values.maxRounds)
                                        .cancel(values.cancel)
                                        .newUserTurns(Collections.singletonList(turn))
                                        .build())
                        : scenario.loop.resume("u", "s", values.call, values.tools,
                                Collections.<AgentLifecycle>singletonList(scenario.hook),
                                values.maxRounds, values.cancel, Collections.singletonList(turn));
                scenario.responses.add(result.getResult().getContent());
            }
            scenario.snapshot();
            return scenario;
        }

        private void snapshot() {
            transportCalls = transport.requests().size();
            hookCalls = hook.afterModelCalls;
            for (LlmRequest request : transport.requests()) {
                for (LlmMessage message : request.getMessages()) {
                    requestRoles.add(String.valueOf(message.getRole()));
                    requestTexts.add(message.getText());
                }
            }
            Optional<AgentSession> stored = store.find("u", "s");
            if (stored.isPresent()) {
                sessionVersion = (int) stored.get().getVersion();
                for (AgentSessionMessage message : stored.get().getMessages()) {
                    storedRoles.add(String.valueOf(message.getRole()));
                    storedTexts.add(message.getText());
                }
            }
        }
    }

    /**
     * 两条入口共享的一组具体参数值。
     */
    private static final class LegendValues {

        private final LlmCall call;
        private final List<LlmTool> tools;
        private final Integer maxRounds;
        private final LlmCancelHandle cancel;

        private LegendValues(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                             LlmCancelHandle cancel) {
            this.call = call;
            this.tools = tools;
            this.maxRounds = maxRounds;
            this.cancel = cancel;
        }

        static LegendValues defaults() {
            return new LegendValues(
                    LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                    Collections.<LlmTool>emptyList(), Integer.valueOf(4), null);
        }

        static LegendValues standard() {
            return new LegendValues(
                    LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                    Collections.<LlmTool>emptyList(), Integer.valueOf(2),
                    new LlmCancelHandle());
        }
    }

    /**
     * 计数钩子：只验证"调用方钩子确实被串进链路"，不改变任何内容。
     */
    private static final class CountingHook implements AgentLifecycle {

        private int afterModelCalls;

        @Override
        public void afterModel(io.github.yinwenyuan28.ailite.assembly.AgentLoopContext ctx,
                               io.github.yinwenyuan28.ailite.assembly.AgentModelOutput output) {
            afterModelCalls++;
        }
    }
}

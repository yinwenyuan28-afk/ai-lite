package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同步流式：增量类型分流与拼接一致性。
 *
 * <p>核心断言：{@code CONTENT} 拼接结果等于 {@code getContent()}，
 * {@code REASONING} 拼接结果等于 {@code getThinkingText()}，且两者都不含 {@code "\n\n"}
 * 分隔符——这是引入 {@link DeltaKind} 的全部理由。</p>
 */
public class AgentLoopStreamTest {

    // ---------------------------------------------------- 类型分流与拼接

    @Test
    public void contentDeltasConcatenateToContentAndExcludeSeparator() {
        Harness harness = Harness.create();
        harness.enqueueStreaming("reasoning-part", "answer-part");

        LlmResult result = harness.loop.runStream(call(), null, null, Integer.valueOf(4), null,
                harness.listener);

        assertThat(result.getContent()).isEqualTo("answer-part");
        assertThat(result.getThinkingText()).isEqualTo("reasoning-part");

        assertThat(harness.contentText()).isEqualTo("answer-part");
        assertThat(harness.reasoningText()).isEqualTo("reasoning-part");
        // 分隔符绝不能出现在任一类增量里。
        assertThat(harness.contentText()).doesNotContain("\n\n");
        assertThat(harness.reasoningText()).doesNotContain("\n\n");
    }

    @Test
    public void contentOnlyModelHasNoReasoningDelta() {
        Harness harness = Harness.create();
        harness.enqueueStreaming(null, "plain-answer");
        LlmResult result = harness.loop.runStream(call(), null, null, Integer.valueOf(4), null,
                harness.listener);

        assertThat(result.getContent()).isEqualTo("plain-answer");
        assertThat(harness.reasoningText()).isEmpty();
        assertThat(harness.contentText()).isEqualTo("plain-answer");
    }

    @Test
    public void multipleRoundsCarryIncrementingRoundNumber() {
        Harness harness = Harness.create();
        // 第 1 轮带工具调用，第 2 轮收尾。
        harness.enqueue(new LlmCompletion("", "think-1", LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))));
        harness.enqueue(new LlmCompletion("done", null, LlmStopReason.STOP, LlmUsage.zero(), null));

        LlmResult result = harness.loop.runStream(call(),
                Collections.singletonList(echoTool()), null, Integer.valueOf(4), null,
                harness.listener);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(harness.rounds()).contains(1, 2);
        // 第 2 轮才有正文增量。
        assertThat(harness.contentRounds()).containsExactly(Integer.valueOf(2));
    }

    @Test
    public void toolExecutionPhaseEmitsNoDeltas() {
        Harness harness = Harness.create();
        harness.enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))));
        harness.enqueue(new LlmCompletion("done", null, LlmStopReason.STOP, LlmUsage.zero(), null));

        harness.loop.runStream(call(), Collections.singletonList(echoTool()), null,
                Integer.valueOf(4), null, harness.listener);

        // 工具返回的大段文本不能混进增量（增量只来自模型）。
        assertThat(harness.contentText()).isEqualTo("done");
    }

    // ------------------------------------------------------------ 线程契约

    @Test
    public void deltasRunOnCallerThread() {
        Harness harness = Harness.create();
        harness.enqueueStreaming(null, "answer");
        final Thread caller = Thread.currentThread();

        harness.loop.runStream(call(), null, null, Integer.valueOf(4), null,
                new AgentDeltaListener() {
                    @Override
                    public void onDelta(int round, DeltaKind kind, String text) {
                        assertThat(Thread.currentThread()).isSameAs(caller);
                    }
                });
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    public void nullListenerIsRejected() {
        Harness harness = Harness.create();

        assertThatThrownBy(() -> harness.loop.runStream(call(), null, null, Integer.valueOf(4),
                null, null))
                .isInstanceOf(io.github.yinwenyuan28.ailite.llm.LlmCallException.class)
                .hasMessageContaining("流式回调不能为空");
    }

    // ------------------------------------------------- 非流式路径不受影响

    @Test
    public void nonStreamingRoundStillWorksAndEmitsNothing() {
        Harness harness = Harness.create();
        harness.enqueueStreaming(null, "answer");

        // run 不带 listener：底层不应请求流式。
        LlmResult result = harness.loop.run(call(), null, null, Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(harness.listener.events).isEmpty();
        // 传输层收到的 stream 标志必须是 false。
        assertThat(harness.transport.requests().get(0).isStream()).isFalse();
    }

    // ------------------------------------------------------------- 夹具

    private static LlmCall call() {
        return LlmCall.profile("biz", "main",
                Collections.singletonList(LlmMessage.user("hi")));
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
                return "tool-output-should-not-become-delta";
            }
        };
    }

    /**
     * 流式假传输：按请求里的类型化 sink 推增量，再返回完整结果。
     *
     * <p>刻意不在 sink 模式下推 {@code "\n\n"}，这正是要与旧无类型通道区分的行为。</p>
     */
    private static final class StreamingTransport implements LlmTransport {

        private final LinkedList<LlmCompletion> responses = new LinkedList<LlmCompletion>();
        private final List<LlmRequest> requests = new ArrayList<LlmRequest>();

        StreamingTransport enqueue(LlmCompletion completion) {
            responses.add(completion);
            return this;
        }

        List<LlmRequest> requests() {
            return requests;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            LlmCompletion completion = responses.isEmpty()
                    ? new LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null)
                    : responses.removeFirst();
            if (!request.isStream() || request.getDeltaSink() == null) {
                return completion;
            }
            String thinking = completion.getThinkingText();
            String content = completion.getContent();
            if (thinking != null && !thinking.isEmpty()) {
                request.getDeltaSink().reasoning(thinking);
            }
            if (content != null && !content.isEmpty()) {
                request.getDeltaSink().content(content);
            }
            return completion;
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            return CompletableFuture.completedFuture(complete(request));
        }
    }

    /**
     * 记录增量事件，便于断言拼接结果与轮次。
     */
    private static final class RecordingListener implements AgentDeltaListener {

        private final List<String> events = new CopyOnWriteArrayList<String>();
        private final List<Integer> roundsSeen = new CopyOnWriteArrayList<Integer>();
        private final List<Integer> contentRoundsSeen = new CopyOnWriteArrayList<Integer>();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();

        @Override
        public void onDelta(int round, DeltaKind kind, String text) {
            events.add(kind + ":" + text);
            roundsSeen.add(Integer.valueOf(round));
            if (kind == DeltaKind.REASONING) {
                reasoning.append(text);
            } else {
                content.append(text);
                contentRoundsSeen.add(Integer.valueOf(round));
            }
        }
    }

    private static final class Harness {

        private final StreamingTransport transport;
        private final RecordingListener listener = new RecordingListener();
        private final AgentLoop loop;

        private Harness(StreamingTransport transport, AgentLoop loop) {
            this.transport = transport;
            this.loop = loop;
        }

        static Harness create() {
            StreamingTransport transport = new StreamingTransport();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            return new Harness(transport, new AgentLoop(gateway));
        }

        void enqueue(LlmCompletion completion) {
            transport.enqueue(completion);
        }

        void enqueueStreaming(String thinking, String content) {
            transport.enqueue(new LlmCompletion(content, thinking, LlmStopReason.STOP,
                    LlmUsage.zero(), null));
        }

        String contentText() {
            return listener.content.toString();
        }

        String reasoningText() {
            return listener.reasoning.toString();
        }

        List<Integer> rounds() {
            return listener.roundsSeen;
        }

        List<Integer> contentRounds() {
            return listener.contentRoundsSeen;
        }
    }
}

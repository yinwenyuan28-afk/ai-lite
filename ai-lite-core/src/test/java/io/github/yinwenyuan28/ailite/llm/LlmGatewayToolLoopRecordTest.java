package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环的记录与异常收口：每轮一条记录、终态记录不被 TOOL_USE 吞掉、
 * 工具执行失败/取消都有对应的终态记录。
 */
public class LlmGatewayToolLoopRecordTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void toolLoopPreCancelReturnsAbortedWithoutRequest() {
        SyncScriptedTransport transport = new SyncScriptedTransport().enqueue(stop("never"));
        RecordingStore store = new RecordingStore();
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        LlmResult result = gateway(transport, fixed(null), store)
                .toolLoop(llmCall(), tools(), Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED.wire());
    }

    @Test
    public void syncToolExecutorThrowIsWrappedAndRecorded() {
        SyncScriptedTransport transport = new SyncScriptedTransport().enqueue(toolUse("c1"));
        RecordingStore store = new RecordingStore();

        assertThatThrownBy(() -> gateway(transport, new ThrowingToolExecutor(), store)
                .toolLoop(llmCall(), tools(), Integer.valueOf(4)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具执行失败")
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(IllegalStateException.class));

        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.TOOL_USE.wire());
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.ERROR.wire());
        assertThat(store.records.get(1).getSuccess()).isEqualTo(Integer.valueOf(0));
    }

    @Test
    public void syncToolExecutorNullResultIsTreatedAsEmpty() {
        SyncScriptedTransport transport = new SyncScriptedTransport()
                .enqueue(toolUse("c1")).enqueue(stop("done"));
        RecordingStore store = new RecordingStore();

        LlmResult result = gateway(transport, fixed(null), store)
                .toolLoop(llmCall(), tools(), Integer.valueOf(4));

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(store.records).hasSize(2);
        List<LlmMessage> second = transport.requests.get(1).getMessages();
        assertThat(second.get(second.size() - 1).getText()).isEqualTo("");
    }

    @Test
    public void syncToolLoopRecordsEachRoundWithCumulativeUsage() {
        SyncScriptedTransport transport = new SyncScriptedTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE,
                        LlmUsage.of(1, 2, 3), toolCalls("c1")))
                .enqueue(new LlmCompletion("done", null, LlmStopReason.STOP,
                        LlmUsage.of(4, 5, 9), null));
        RecordingStore store = new RecordingStore();

        gateway(transport, fixed(Collections.singletonList("ok")), store)
                .toolLoop(llmCall(), tools(), Integer.valueOf(4));

        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.TOOL_USE.wire());
        assertThat(store.records.get(0).getTotalTokens()).isEqualTo(Integer.valueOf(3));
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.STOP.wire());
        assertThat(store.records.get(1).getTotalTokens()).isEqualTo(Integer.valueOf(12));
    }

    @Test
    public void syncCancelAfterToolsRecordsAborted() {
        SyncScriptedTransport transport = new SyncScriptedTransport().enqueue(toolUse("c1"));
        RecordingStore store = new RecordingStore();
        LlmCancelHandle cancel = new LlmCancelHandle();

        LlmResult result = gateway(transport, new CancellingToolExecutor(cancel), store)
                .toolLoop(llmCall(), tools(), Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED.wire());
    }

    @Test
    public void asyncToolLoopWritesTerminalRecord() throws Exception {
        AsyncScriptedTransport transport = new AsyncScriptedTransport()
                .enqueue(toolUse("c1")).enqueue(stop("done"));
        RecordingStore store = new RecordingStore();

        LlmResult result = gateway(transport, fixed(Collections.singletonList("ok")), store)
                .toolLoopAsync(llmCall(), tools(), Integer.valueOf(4))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.TOOL_USE.wire());
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.STOP.wire());
    }

    @Test
    public void asyncMaxRoundsRecordIsNotSwallowed() throws Exception {
        AsyncScriptedTransport transport = new AsyncScriptedTransport()
                .enqueue(toolUse("c1")).enqueue(toolUse("c2"));
        RecordingStore store = new RecordingStore();

        LlmResult result = gateway(transport, fixed(Collections.singletonList("ok")), store)
                .toolLoopAsync(llmCall(), tools(), Integer.valueOf(2))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.MAX_ROUNDS.wire());
    }

    @Test
    public void asyncToolFailureRecordsError() throws Exception {
        AsyncScriptedTransport transport = new AsyncScriptedTransport().enqueue(toolUse("c1"));
        RecordingStore store = new RecordingStore();
        final CompletableFuture<List<String>> failed = new CompletableFuture<List<String>>();
        failed.completeExceptionally(new IllegalStateException("tool boom"));

        try {
            gateway(transport, new AsyncFixedToolExecutor(failed, null), store)
                    .toolLoopAsync(llmCall(), tools(), Integer.valueOf(4))
                    .get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
            assertThat(ex.getCause().getMessage()).contains("工具执行失败");
        }

        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.ERROR.wire());
    }

    @Test
    public void asyncCancelAfterToolsRecordsAborted() throws Exception {
        AsyncScriptedTransport transport = new AsyncScriptedTransport().enqueue(toolUse("c1"));
        RecordingStore store = new RecordingStore();
        LlmCancelHandle cancel = new LlmCancelHandle();
        final CompletableFuture<List<String>> completed =
                CompletableFuture.completedFuture(Collections.<String>emptyList());

        LlmResult result = gateway(transport, new AsyncFixedToolExecutor(completed, cancel), store)
                .toolLoopAsync(llmCall(), tools(), Integer.valueOf(4), cancel)
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED.wire());
    }

    // ---------------------------------------------------------------- 工具与替身

    private static LlmGateway gateway(LlmTransport transport, ToolExecutor toolExecutor,
                                      LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), toolExecutor);
    }

    private static LlmCall llmCall() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static List<LlmTool> tools() {
        return Collections.singletonList(new LlmTool() {
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
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "ok";
            }
        });
    }

    private static List<LlmToolCall> toolCalls(String id) {
        return Collections.singletonList(new LlmToolCall(id, "echo", "{}"));
    }

    private static LlmCompletion toolUse(String id) {
        return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                toolCalls(id));
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static ToolExecutor fixed(final List<String> results) {
        return new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                return results;
            }
        };
    }

    private static final class ThrowingToolExecutor implements ToolExecutor {
        @Override
        public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
            throw new IllegalStateException("tool boom");
        }
    }

    private static final class CancellingToolExecutor implements ToolExecutor {

        private final LlmCancelHandle cancel;

        private CancellingToolExecutor(LlmCancelHandle cancel) {
            this.cancel = cancel;
        }

        @Override
        public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
            cancel.cancel();
            return Collections.emptyList();
        }
    }

    private static final class AsyncFixedToolExecutor implements ToolExecutor {

        private final CompletableFuture<List<String>> future;
        private final LlmCancelHandle cancel;

        private AsyncFixedToolExecutor(CompletableFuture<List<String>> future,
                                       LlmCancelHandle cancel) {
            this.future = future;
            this.cancel = cancel;
        }

        @Override
        public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
            throw new UnsupportedOperationException("async only");
        }

        @Override
        public CompletableFuture<List<String>> executeAsync(List<LlmTool> tools,
                                                            List<LlmToolCall> calls) {
            if (cancel != null) {
                cancel.cancel();
            }
            return future;
        }
    }

    private static final class SyncScriptedTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        private final Deque<Object> outcomes = new ArrayDeque<Object>();

        SyncScriptedTransport enqueue(LlmCompletion completion) {
            outcomes.addLast(completion);
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            Object outcome = outcomes.isEmpty() ? stop("") : outcomes.removeFirst();
            if (outcome instanceof LlmCallException) {
                throw (LlmCallException) outcome;
            }
            return (LlmCompletion) outcome;
        }
    }

    private static final class AsyncScriptedTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        private final Deque<Object> outcomes = new ArrayDeque<Object>();

        AsyncScriptedTransport enqueue(LlmCompletion completion) {
            outcomes.addLast(completion);
            return this;
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
            requests.add(request);
            Object outcome = outcomes.isEmpty() ? stop("") : outcomes.removeFirst();
            CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            if (outcome instanceof LlmCallException) {
                future.completeExceptionally((LlmCallException) outcome);
            } else {
                future.complete((LlmCompletion) outcome);
            }
            return future;
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

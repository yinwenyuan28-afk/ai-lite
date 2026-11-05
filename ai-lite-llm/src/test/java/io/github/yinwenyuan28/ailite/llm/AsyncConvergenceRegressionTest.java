package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-01 对抗性用例：回调体里任何一步抛异常，返回给调用方的 future 都必须收敛。
 *
 * <p>批次 A 只补用例。改前这些用例会因为 future 永不完成而在 get(3s) 处超时失败。</p>
 */
public class AsyncConvergenceRegressionTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));
    private static final long TIMEOUT_SECONDS = 3L;

    @Test
    public void callBodyPolicyFailureDoesNotHangFuture() {
        LlmGateway gateway = gateway(immediateTransport(completion("ok", LlmStopReason.STOP, null)));
        gateway.setCallBodyPolicy(new CallBodyPolicy() {
            @Override
            public String level() {
                throw new IllegalStateException("CallBodyPolicy.level 炸了");
            }
        });

        CompletableFuture<LlmResult> future =
                gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES));

        // 改前：recordTerminal 抛异常 → completeBarrier 没跑到 → get 超时。
        // 改后：safeWhenComplete 兜底把异常翻成 future 的异常完成。
        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("CallBodyPolicy.level 炸了");
    }

    @Test
    public void errorFromCallBodyPolicyDoesNotHangFuture() {
        LlmGateway gateway = gateway(immediateTransport(completion("ok", LlmStopReason.STOP, null)));
        gateway.setCallBodyPolicy(new CallBodyPolicy() {
            @Override
            public String level() {
                throw new AssertionError("CallBodyPolicy.level 抛了 Error");
            }
        });

        CompletableFuture<LlmResult> future =
                gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES));

        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("Error");
    }

    @Test
    public void errorFromToolExecutorDoesNotHangFuture() {
        // startRound 的工具调用只 catch(RuntimeException)，Error 会冒泡到外层
        // whenComplete 回调体（发生在 completeBarrier 之前）。
        LlmGateway gateway = gateway(
                immediateTransport(toolUse()), errorThrowingToolExecutor());

        CompletableFuture<LlmResult> future = gateway.toolLoopAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(echoTool()), Integer.valueOf(3));

        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具执行器抛了 Error");
    }

    @Test
    public void terminalRecordedExactlyOnceWhenExtensionPointFails() throws Exception {
        CountingStore store = new CountingStore();
        LlmGateway gateway = gateway(
                immediateTransport(completion("ok", LlmStopReason.STOP, null)), store);
        gateway.setCallBodyPolicy(new CallBodyPolicy() {
            @Override
            public String level() {
                throw new IllegalStateException("level boom");
            }
        });

        CompletableFuture<LlmResult> future =
                gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES));
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // 异常完成是预期结局；这里只关心记录条数。
        } catch (java.util.concurrent.TimeoutException hung) {
            org.junit.Assert.fail("future 永久挂起，且终态记录没有写出");
        }

        // 记录按回调体前段当时的结局写出；扩展点异常由兜底收敛 future，
        // 不会再补写第二条。硬契约是「恰好一条、不因异常丢记录」。
        assertThat(store.rows).hasSize(1);
    }

    @Test
    public void errorFromDeltaDoesNotHangInIoMode() throws Exception {
        // F-02 与 F-01 的合流验证：io 模式下 onDelta 抛 Error，既不能让回调通道
        // 停摆，也不能让完成屏障任务丢失。
        java.util.concurrent.ExecutorService callbackPool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            LlmGateway gateway = gateway(streamingTransport("hello"));
            gateway.setCallbackPolicy(CallbackPolicy.io(callbackPool, 64));

            CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(
                    LlmCall.profile("biz", "main", MESSAGES),
                    new java.util.function.Consumer<String>() {
                        @Override
                        public void accept(String text) {
                            throw new AssertionError("onDelta 抛了 Error");
                        }
                    }, null);

            assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LlmCallException.class)
                    .hasMessageContaining("onDelta 回调失败");
        } finally {
            callbackPool.shutdownNow();
        }
    }

    @Test
    public void bodyPolicySnapshotKeepsExactlyOneRecord() throws Exception {
        // F-08：开启正文记录后，record() 走防御性复制路径，终态记录仍恰好一条。
        CountingStore store = new CountingStore();
        LlmGateway gateway = gateway(immediateTransport(completion("ok", LlmStopReason.STOP, null)),
                store);
        gateway.setCallBodyPolicy(new CallBodyPolicy() {
            @Override
            public String level() {
                return CallBodyPolicy.ALL;
            }
        });

        LlmResult result = gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.get(0).getInputBody()).isNotNull();
    }

    @Test
    public void retryPolicyFailureDoesNotHangFuture() {
        // RetryRun.onAttemptComplete 同样是无保护的 whenComplete 回调体。
        LlmGateway gateway = gateway(immediateFailingTransport(new LlmCallException("boom")));
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .retryable(new java.util.function.Predicate<LlmCallException>() {
                    @Override
                    public boolean test(LlmCallException ex) {
                        throw new IllegalStateException("RetryPolicy.retryable 炸了");
                    }
                })
                .build();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(
                LlmCall.profile("biz", "main", MESSAGES), policy);

        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("RetryPolicy.retryable 炸了");
    }

    // ---------------------------------------------------------------- 支撑件

    private static LlmGateway gateway(LlmTransport transport) {
        return gateway(transport, new CountingStore());
    }

    private static LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmGateway gateway(LlmTransport transport, ToolExecutor toolExecutor) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new CountingStore(),
                new DirectExecutorProvider(), toolExecutor);
    }

    private static LlmTransport immediateTransport(final LlmCompletion completion) {
        return new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return completion;
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.completedFuture(completion);
            }
        };
    }

    private static LlmTransport streamingTransport(final String delta) {
        return new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return completion(delta, LlmStopReason.STOP, null);
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                if (request.getOnDelta() != null) {
                    request.getOnDelta().accept(delta);
                }
                return CompletableFuture.completedFuture(
                        completion(delta, LlmStopReason.STOP, null));
            }
        };
    }

    private static LlmTransport immediateFailingTransport(final LlmCallException failure) {
        return new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw failure;
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
                future.completeExceptionally(failure);
                return future;
            }
        };
    }

    private static LlmCompletion completion(String content, LlmStopReason stop,
                                            List<LlmToolCall> calls) {
        return new LlmCompletion(content, null, stop, LlmUsage.of(1, 1, 2), calls);
    }

    private static LlmCompletion toolUse() {
        return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(new LlmToolCall("c1", "echo", "{}")));
    }

    private static ToolExecutor errorThrowingToolExecutor() {
        return new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                throw new AssertionError("工具执行器抛了 Error");
            }

            @Override
            public CompletableFuture<List<String>> executeAsync(List<LlmTool> tools,
                                                                List<LlmToolCall> calls) {
                throw new AssertionError("工具执行器抛了 Error");
            }
        };
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
                return "ok";
            }
        };
    }

    private static final class CountingStore implements LlmCallStore {
        private final List<LlmCallRecord> rows =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            rows.add(record);
        }
    }
}

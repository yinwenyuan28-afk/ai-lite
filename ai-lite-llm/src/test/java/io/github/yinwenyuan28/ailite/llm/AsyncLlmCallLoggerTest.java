package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.yinwenyuan28.ailite.internal.llm.AsyncLlmCallLogger;

/**
 * 异步调用日志：异步落库、异常隔离、线程池拒绝和空实现兜底。
 */
public class AsyncLlmCallLoggerTest {

    @Test
    public void recordInsertsRowAndFillsMetadata() throws Exception {
        RecordingStore store = new RecordingStore();
        final CountDownLatch done = new CountDownLatch(1);
        AsyncLlmCallLogger logger = new AsyncLlmCallLogger(new ImmediateProvider(done), store);
        LlmCallRecord row = new LlmCallRecord();
        row.setBizId("biz");

        logger.record(row);

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.rows).hasSize(1);
        LlmCallRecord saved = store.rows.get(0);
        assertThat(saved.getBizId()).isEqualTo("biz");
        assertThat(saved.getCallId()).isNotBlank();
        assertThat(saved.getCreateTime()).isNotNull();
    }

    @Test
    public void nullRowIsIgnored() {
        RecordingStore store = new RecordingStore();
        new AsyncLlmCallLogger(new DirectProvider(), store).record(null);

        assertThat(store.rows).isEmpty();
    }

    @Test
    public void storeFailureDoesNotEscape() {
        LlmCallStore broken = new LlmCallStore() {
            @Override
            public void insert(LlmCallRecord record) {
                throw new IllegalStateException("db down");
            }
        };
        LlmCallRecord row = new LlmCallRecord();
        row.setBizId("biz");

        new AsyncLlmCallLogger(new DirectProvider(), broken).record(row);

        // 走到这里说明异常被吞掉了。
        assertThat(row.getCallId()).isNotBlank();
    }

    @Test
    public void rejectedExecutionDoesNotEscape() {
        ExecutorProvider rejecting = new ExecutorProvider() {
            @Override
            public Executor io() {
                return new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        throw new RejectedExecutionException("full");
                    }
                };
            }

            @Override
            public void close() {
            }
        };
        LlmCallRecord row = new LlmCallRecord();
        row.setBizId("biz");

        new AsyncLlmCallLogger(rejecting, new RecordingStore()).record(row);

        assertThat(row.getCallId()).isNotBlank();
    }

    @Test
    public void nullExecutorAndStoreFallBackToSafeDefaults() {
        LlmCallRecord row = new LlmCallRecord();
        row.setBizId("biz");

        new AsyncLlmCallLogger(null, null).record(row);

        assertThat(row.getCallId()).isNotBlank();
    }

    @Test
    public void asyncExecutorActuallyRunsOffCallerThread() throws Exception {
        final RecordingStore store = new RecordingStore();
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        ExecutorProvider provider = new ExecutorProvider() {
            @Override
            public Executor io() {
                return new Executor() {
                    @Override
                    public void execute(final Runnable command) {
                        Thread thread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                threads.add(Thread.currentThread().getName());
                                command.run();
                                done.countDown();
                            }
                        }, "logger-worker");
                        thread.setDaemon(true);
                        thread.start();
                    }
                };
            }

            @Override
            public void close() {
            }
        };

        new AsyncLlmCallLogger(provider, store).record(new LlmCallRecord());

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(threads).containsExactly("logger-worker");
        assertThat(store.rows).hasSize(1);
    }

    /**
     * 同步执行器，便于断言落库结果。
     */
    private static final class DirectProvider implements ExecutorProvider {
        @Override
        public Executor io() {
            return new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
        }

        @Override
        public void close() {
        }
    }

    /**
     * 执行后计数，用于异步场景。
     */
    private static final class ImmediateProvider implements ExecutorProvider {
        private final CountDownLatch done;

        private ImmediateProvider(CountDownLatch done) {
            this.done = done;
        }

        @Override
        public Executor io() {
            return new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                    done.countDown();
                }
            };
        }

        @Override
        public void close() {
        }
    }

    /**
     * 记录写入的行。
     */
    private static final class RecordingStore implements LlmCallStore {
        private final List<LlmCallRecord> rows =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            rows.add(record);
        }
    }
}

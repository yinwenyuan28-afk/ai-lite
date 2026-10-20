package io.github.yinwenyuan28.ailite.executor;

import java.util.concurrent.Executor;

/**
 * 调用线程内联执行。用于测试和确定不需要异步的场景。
 */
public final class DirectExecutorProvider implements ExecutorProvider {

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Override
    public Executor io() {
        return DIRECT;
    }

    @Override
    public void close() {
        // 没有资源需要释放。
    }
}

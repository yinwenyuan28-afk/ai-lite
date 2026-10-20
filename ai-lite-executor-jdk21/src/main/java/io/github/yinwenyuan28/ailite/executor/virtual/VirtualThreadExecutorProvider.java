package io.github.yinwenyuan28.ailite.executor.virtual;

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDK 21 虚拟线程执行器。
 *
 * <p>本模块用 JDK 21 编译，主工程仍是 JDK 8 字节码。Starter 通过类名反射加载，
 * 所以 JDK 8 运行时不会碰到任何虚拟线程 API。</p>
 */
public final class VirtualThreadExecutorProvider implements ExecutorProvider {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Executor io() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

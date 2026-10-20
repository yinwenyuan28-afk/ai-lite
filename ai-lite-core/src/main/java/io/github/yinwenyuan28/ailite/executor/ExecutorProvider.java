package io.github.yinwenyuan28.ailite.executor;

import java.io.Closeable;
import java.util.concurrent.Executor;

/**
 * 异步任务执行器 SPI。网关的日志、未来并行工具执行都从这里取线程池。
 */
public interface ExecutorProvider extends Closeable {

    /**
     * @return 用于阻塞 I/O 任务的执行器
     */
    Executor io();

    @Override
    void close();
}

package io.github.yinwenyuan28.ailite.http.okhttp.jdk21;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpClientFactory;
import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import okhttp3.OkHttpClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * B 路径：OkHttp Dispatcher 改用虚拟线程。
 *
 * <p>同步入口 {@code one}/{@code oneWithRetry} 仍在调用线程上阻塞；只有
 * {@code enqueue} 的异步路径受益。连接池、超时与“关闭连接级重试”的语义与默认
 * OkHttp 完全一致。</p>
 *
 * <p>JDK 21–23 的 {@code synchronized} 可能 pin 载体线程，高并发场景建议 JDK 24+。</p>
 */
public final class VirtualThreadOkHttpFactory {

    private VirtualThreadOkHttpFactory() {
    }

    /**
     * 创建虚拟线程 Dispatcher 的 OkHttp 执行器。
     *
     * @param config HTTP 配置，可为 null
     * @return 执行器；调用方负责 close
     */
    public static HttpExecutor create(AiLiteConfig.Http config) {
        ThreadFactory factory = Thread.ofVirtual()
                .name("ai-lite-http-virtual-", 0)
                .factory();
        ExecutorService dispatcherExecutor = Executors.newThreadPerTaskExecutor(factory);
        OkHttpClient client = OkHttpClientFactory.create(config, dispatcherExecutor);
        return new OkHttpExecutor(client, true);
    }
}

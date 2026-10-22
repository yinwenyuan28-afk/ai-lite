package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.util.concurrent.ExecutorService;

/**
 * 按 {@code ai-lite.http.*} 构造 OkHttp 客户端。
 *
 * <p>B 路径（JDK 21 虚拟线程）复用这里的连接池、超时与重试语义，只替换
 * Dispatcher 的承载线程。关闭连接级自动重试，避免和 {@code oneWithRetry} 叠加。</p>
 */
public final class OkHttpClientFactory {

    /** OkHttp Dispatcher 默认值，与 OkHttp 自身保持一致。 */
    public static final int DEFAULT_MAX_REQUESTS = 64;
    public static final int DEFAULT_MAX_REQUESTS_PER_HOST = 5;

    private OkHttpClientFactory() {
    }

    /**
     * 创建默认 OkHttp 执行器。
     *
     * @param config HTTP 配置，可为 null
     * @return 执行器；调用方负责 close
     */
    public static HttpExecutor executor(AiLiteConfig.Http config) {
        return new OkHttpExecutor(create(config), true);
    }

    /**
     * 创建 OkHttp 客户端。
     *
     * @param config             HTTP 配置，可为 null
     * @param dispatcherExecutor 自定义 Dispatcher 线程池；null 表示 OkHttp 默认
     * @return 客户端
     */
    public static OkHttpClient create(AiLiteConfig.Http config, ExecutorService dispatcherExecutor) {
        Dispatcher dispatcher = dispatcherExecutor == null
                ? new Dispatcher()
                : new Dispatcher(dispatcherExecutor);
        dispatcher.setMaxRequests(maxRequests(config));
        dispatcher.setMaxRequestsPerHost(maxRequestsPerHost(config));
        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * @param config HTTP 配置，可为 null
     * @return 使用默认 Dispatcher 的客户端
     */
    public static OkHttpClient create(AiLiteConfig.Http config) {
        return create(config, null);
    }

    private static int maxRequests(AiLiteConfig.Http config) {
        Integer value = config == null ? null : config.getDispatcher().getMaxRequests();
        return positive(value, DEFAULT_MAX_REQUESTS, "ai-lite.http.dispatcher.max-requests");
    }

    private static int maxRequestsPerHost(AiLiteConfig.Http config) {
        Integer value = config == null ? null : config.getDispatcher().getMaxRequestsPerHost();
        return positive(value, DEFAULT_MAX_REQUESTS_PER_HOST,
                "ai-lite.http.dispatcher.max-requests-per-host");
    }

    private static int positive(Integer value, int fallback, String key) {
        if (value == null) {
            return fallback;
        }
        if (value.intValue() <= 0) {
            throw new LlmCallException(key + " 必须大于 0", null,
                    LlmCallException.Kind.CONFIG);
        }
        return value.intValue();
    }
}

package io.github.yinwenyuan28.ailite.http;

import java.util.concurrent.CompletableFuture;

/**
 * 支持非阻塞调用的 HTTP 执行器。
 *
 * <p>JDK 8 没有虚拟线程，用户级并发的关键在于不要在等待响应时占住线程。
 * OkHttp 通过 {@code enqueue} 把等待交给连接和回调，天然满足这一点；
 * 基于 {@code HttpURLConnection} 的实现则退化为把阻塞调用交给线程池。</p>
 *
 * <p><b>演化承诺（B 组：方法集终态）</b>：与 {@link HttpExecutor} 同属技术底层，
 * 1.0 后不再新增方法；新能力走新接口。</p>
 */
public interface AsyncHttpExecutor extends HttpExecutor {

    /**
     * 非阻塞执行。返回的 future 由实现负责完成；调用方取消 future 时应尽量中断底层请求。
     *
     * @param request 请求
     * @return 响应 future
     */
    CompletableFuture<HttpResult> executeAsync(HttpRequest request);

    /**
     * 非阻塞流式执行。行回调在实现自己的线程上执行，不要在里面做长时间阻塞操作。
     *
     * @param request  请求
     * @param listener 行回调
     * @return 流结束或失败时完成的 future
     */
    CompletableFuture<Void> executeStreamingAsync(HttpRequest request, StreamListener listener);
}

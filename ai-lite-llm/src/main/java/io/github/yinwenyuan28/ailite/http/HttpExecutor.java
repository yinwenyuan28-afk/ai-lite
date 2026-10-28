package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.io.Closeable;

/**
 * HTTP 执行器 SPI。默认实现是 OkHttp，另有零依赖的 HttpURLConnection 实现。
 *
 * <p><b>演化承诺（B 组：方法集终态）</b>：本接口的形状由 HTTP 协议语义决定，
 * 已足够完整，1.0 后不再新增方法。需要新能力（连接池探活、请求级重试等）
 * 应新增独立接口，而不是往这里塞——往老接口加抽象方法会让所有第三方实现
 * 当场编译失败。</p>
 */
public interface HttpExecutor extends Closeable {

    /**
     * 执行非流式请求。
     *
     * @param request 请求
     * @return 状态码与完整响应体
     * @throws LlmCallException 网络失败、超时、取消或状态码非 2xx 时抛出
     */
    HttpResult execute(HttpRequest request);

    /**
     * 执行流式请求，按行回调。状态码非 2xx 时抛出。
     *
     * @param request  请求
     * @param listener 行回调
     * @throws LlmCallException 网络失败、超时、取消或状态码非 2xx 时抛出
     */
    void executeStreaming(HttpRequest request, StreamListener listener);

    /**
     * 释放连接池和线程资源。
     */
    @Override
    void close();
}

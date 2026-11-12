package io.github.yinwenyuan28.ailite.http.webclient;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.http.Timeouts;
import io.github.yinwenyuan28.ailite.internal.http.Utf8LineBuffer;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring WebClient 5.1 API 传输。
 *
 * <p>源码按 WebFlux 5.1 编译，避免使用 5.2/5.3 才有的 {@code bodyValue}、
 * {@code exchangeToMono} 等 API，因此同一份字节码可以运行在 Boot 2.1 和 2.7。
 * SSE 直接消费 {@code DataBuffer}，不经过 {@code bodyToFlux(String)}，以保留
 * 空行、{@code data:} 前缀和 {@code [DONE]}。</p>
 *
 * <p>连接资源由独立的 {@link ReactorResourceFactory} 管理，不触碰应用全局的
 * Reactor Netty 资源；{@code close()} 只销毁自己创建的资源。</p>
 */
public final class WebClientHttpExecutor implements AsyncHttpExecutor, RawHttpExecutor {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final ReactorResourceFactory resources;
    private final WebClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param config HTTP 配置，可为 null
     */
    public WebClientHttpExecutor(AiLiteConfig.Http config) {
        this.resources = new ReactorResourceFactory();
        this.resources.setUseGlobalResources(false);
        this.resources.afterPropertiesSet();
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(resources,
                        httpClient -> httpClient))
                .build();
    }

    /**
     * 供 Starter 反射调用的工厂。
     *
     * @param config HTTP 配置
     * @return 执行器
     */
    public static HttpExecutor create(AiLiteConfig.Http config) {
        return new WebClientHttpExecutor(config);
    }

    @Override
    public HttpResult execute(HttpRequest request) {
        CompletableFuture<HttpResult> future = executeAsync(request);
        try {
            // 兜底上界：正常路径由 body 的 timeout 收口；这里防的是上游 future
            // 因任何原因不完成时同步入口永久阻塞。
            return future.get(Timeouts.connect(request) + Timeouts.read(request),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LlmCallException("模型请求超时", ex, LlmCallException.Kind.TRANSPORT);
        } catch (ExecutionException ex) {
            throw unwrap(ex.getCause());
        }
    }

    /**
     * 诊断用：非 2xx 也返回状态码与完整正文。
     *
     * <p>复用同一 {@link WebClient} 与 {@link ReactorResourceFactory}，因此连接池
     * 与 TLS 配置和真实调用一致。</p>
     */
    @Override
    public HttpResult executeRaw(HttpRequest request) {
        CompletableFuture<HttpResult> future = exchangeRaw(request);
        try {
            return future.get(Timeouts.connect(request) + Timeouts.read(request),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LlmCallException("模型请求超时", ex, LlmCallException.Kind.TRANSPORT);
        } catch (ExecutionException ex) {
            throw unwrap(ex.getCause());
        }
    }

    /** {@link #executeRaw} 的异步内核；错误体按上限完整读回，不转成异常。 */
    private CompletableFuture<HttpResult> exchangeRaw(HttpRequest request) {
        final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        try {
            long readMs = Timeouts.read(request);
            Mono<HttpResult> result = requestMono(request, false).flatMap(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(String.class)
                            .timeout(Duration.ofMillis(readMs))
                            .defaultIfEmpty("")
                            .map(body -> new HttpResult(status, body));
                }
                return errorBody(response, readMs).defaultIfEmpty("")
                        .map(body -> new HttpResult(status, body));
            });
            subscribeMono(result, future, request.getCancel());
        } catch (RuntimeException ex) {
            future.completeExceptionally(mapError(ex, request.getCancel()));
        }
        return future;
    }

    @Override
    public void executeStreaming(HttpRequest request, StreamListener listener) {
        CompletableFuture<Void> future = executeStreamingAsync(request, listener);
        try {
            future.get(Timeouts.connect(request) + Timeouts.read(request),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LlmCallException("模型请求超时", ex, LlmCallException.Kind.TRANSPORT);
        } catch (ExecutionException ex) {
            throw unwrap(ex.getCause());
        }
    }

    @Override
    public CompletableFuture<HttpResult> executeAsync(HttpRequest request) {
        final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        try {
            long readMs = Timeouts.read(request);
            Mono<HttpResult> result = requestMono(request, false).flatMap(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is2xxSuccessful()) {
                    // 非流式 body 同样要读超时：服务端先回 200 头再停发 body 时，
                    // 只给 exchange() 加 timeout 是包不住的（F-19）。
                    return response.bodyToMono(String.class)
                            .timeout(Duration.ofMillis(readMs))
                            .defaultIfEmpty("")
                            .map(body -> new HttpResult(status, body));
                }
                return errorBody(response, readMs).defaultIfEmpty("")
                        .flatMap(body -> Mono.<HttpResult>error(httpError(status, body,
                                response.headers().asHttpHeaders().getFirst("Retry-After"))));
            });
            subscribeMono(result, future, request.getCancel());
        } catch (RuntimeException ex) {
            future.completeExceptionally(mapError(ex, request.getCancel()));
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> executeStreamingAsync(final HttpRequest request,
                                                         final StreamListener listener) {
        final CompletableFuture<Void> future = new CompletableFuture<Void>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        try {
            long readMs = Timeouts.read(request);
            Flux<String> lines = requestMono(request, true).flatMapMany(response -> {
                int status = response.statusCode().value();
                if (!response.statusCode().is2xxSuccessful()) {
                    return errorBody(response, readMs).defaultIfEmpty("")
                            .flatMapMany(body -> Flux.<String>error(httpError(status, body,
                                    response.headers().asHttpHeaders().getFirst("Retry-After"))));
                }
                Flux<DataBuffer> body = response.body(BodyExtractors.toDataBuffers())
                        // 取消 / 超时 / 提前终止时，concatMap 预取队列里还没投递的
                        // DataBuffer 不走 mapper 的 finally，必须在这里显式释放，
                        // 否则堆外内存会一直涨（F-25）。
                        .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                        .timeout(Duration.ofMillis(readMs));
                final Utf8LineBuffer buffer = new Utf8LineBuffer(MAX_LINE_BYTES);
                return body.concatMap(dataBuffer -> {
                    byte[] bytes;
                    try {
                        bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    try {
                        return Flux.fromIterable(buffer.accept(bytes));
                    } catch (RuntimeException ex) {
                        return Flux.<String>error(ex);
                    }
                }).concatWith(Flux.defer(() -> {
                    String tail = buffer.flush();
                    return tail == null ? Flux.<String>empty() : Flux.just(tail);
                }));
            });
            subscribeFlux(lines, listener, future, request.getCancel());
        } catch (RuntimeException ex) {
            future.completeExceptionally(mapError(ex, request.getCancel()));
        }
        return future;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            resources.destroy();
        }
    }

    /**
     * 构造请求。连接/响应头等待和 body 读空闲分别用两个 timeout：
     * 前者覆盖建连与首包，后者覆盖读取中的空闲。
     *
     * @param streaming true 时禁用压缩：gzip 缓冲会破坏逐行交付语义
     */
    private Mono<ClientResponse> requestMono(HttpRequest request, boolean streaming) {
        WebClient.RequestHeadersSpec<?> spec;
        if (request.isGet()) {
            spec = client.get().uri(request.getUrl());
        } else {
            WebClient.RequestBodySpec post = client.post().uri(request.getUrl());
            spec = post.body(BodyInserters.fromObject(request.getBody()));
        }
        if (!request.getHeaders().containsKey("Content-Type")) {
            spec = spec.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        }
        final boolean identity = streaming && !request.getHeaders().containsKey("Accept-Encoding");
        spec = spec.headers(headers -> {
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                headers.set(header.getKey(), header.getValue());
            }
            if (identity) {
                headers.set("Accept-Encoding", "identity");
            }
        });
        Mono<ClientResponse> mono = spec.exchange();
        // 无条件设连接超时：0 曾表示「不设」，等价于无限等待。
        mono = mono.timeout(Duration.ofMillis(Timeouts.connect(request)));
        return mono;
    }

    /**
     * 错误体有界读取：只保留前 64KB，其余 DataBuffer 仍然释放。
     *
     * @param readMs 读超时；错误体停在半路同样要收口，不能永久等待
     */
    private static Mono<String> errorBody(ClientResponse response, long readMs) {
        return response.body(BodyExtractors.toDataBuffers())
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .timeout(Duration.ofMillis(readMs))
                .map(dataBuffer -> {
                    try {
                        int readable = Math.min(dataBuffer.readableByteCount(), MAX_ERROR_BYTES);
                        byte[] bytes = new byte[readable];
                        dataBuffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .collect(ByteArrayOutputStream::new, (out, bytes) -> {
                    int allowed = Math.min(bytes.length, MAX_ERROR_BYTES - out.size());
                    if (allowed > 0) {
                        out.write(bytes, 0, allowed);
                    }
                })
                .map(out -> new String(out.toByteArray(), UTF_8));
    }

    private static <T> void subscribeMono(Mono<T> mono, CompletableFuture<T> future,
                                          LlmCancelHandle cancel) {
        final AtomicReference<Disposable> ref = new AtomicReference<Disposable>();
        final AtomicBoolean done = new AtomicBoolean(false);
        bindAbortEarly(future, ref, done, cancel);
        Disposable disposable = mono.subscribe(
                value -> {
                    if (done.compareAndSet(false, true)) {
                        future.complete(value);
                    }
                },
                error -> {
                    if (done.compareAndSet(false, true)) {
                        future.completeExceptionally(mapError(error, cancel));
                    }
                },
                () -> {
                    if (done.compareAndSet(false, true)) {
                        future.complete(null);
                    }
                });
        afterSubscribe(future, ref, done, cancel, disposable);
    }

    private static void subscribeFlux(Flux<String> flux, final StreamListener listener,
                                      final CompletableFuture<Void> future,
                                      final LlmCancelHandle cancel) {
        final AtomicReference<Disposable> ref = new AtomicReference<Disposable>();
        final AtomicBoolean done = new AtomicBoolean(false);
        // 必须先绑 abort 再订阅：流可能同步跑完，若等 subscribe 返回后才绑，
        // 第一行回调里发起的取消会因为 abort 还没绑定而被丢掉（F-20）。
        bindAbortEarly(future, ref, done, cancel);
        Disposable disposable = flux.subscribe(
                line -> {
                    // 除了 done，还要直接看取消标志：同步订阅期间 abort 可能尚未
                    // 执行到 dispose，但 cancelled 已经置位。
                    if (done.get() || (cancel != null && cancel.isCancelled())) {
                        return;
                    }
                    try {
                        listener.onLine(line);
                    } catch (Throwable ex) {
                        if (done.compareAndSet(false, true)) {
                            future.completeExceptionally(streamFailure(ex, cancel));
                            Disposable current = ref.get();
                            if (current != null) {
                                current.dispose();
                            }
                        }
                    }
                },
                error -> {
                    if (done.compareAndSet(false, true)) {
                        future.completeExceptionally(mapError(error, cancel));
                    }
                },
                () -> {
                    if (done.compareAndSet(false, true)) {
                        future.complete(null);
                    }
                });
        afterSubscribe(future, ref, done, cancel, disposable);
    }

    /**
     * 订阅前先注册取消动作。动作只置位 {@code done} 并尽力 dispose；真正的
     * Disposable 在订阅返回后由 {@link #afterSubscribe} 补位。
     */
    private static void bindAbortEarly(final CompletableFuture<?> future,
                                       final AtomicReference<Disposable> ref,
                                       final AtomicBoolean done,
                                       final LlmCancelHandle cancel) {
        if (cancel == null) {
            return;
        }
        cancel.bindAbort(new Runnable() {
            @Override
            public void run() {
                // 先置位再 dispose：并发投递中的 onNext 看到 done 就不再回调。
                done.set(true);
                Disposable current = ref.get();
                if (current != null) {
                    current.dispose();
                }
                // dispose() 不保证触发 onError，需要显式收敛 future。
                if (!future.isDone()) {
                    future.completeExceptionally(cancelled(null));
                }
            }
        });
    }

    /**
     * 订阅返回后补位：处理“已完成/已取消”的竞态，绑上 Disposable，
     * 并让 future.cancel 也能 dispose 底层订阅。
     *
     * <p>{@code done} 必须参与取消路径：只 dispose 不置位时，dispose 前后的
     * onNext / onComplete 仍可能进来，把取消后的增量继续交给调用方（F-20）。</p>
     */
    private static void afterSubscribe(final CompletableFuture<?> future,
                                       final AtomicReference<Disposable> ref,
                                       final AtomicBoolean done,
                                       final LlmCancelHandle cancel,
                                       Disposable disposable) {
        if (future.isDone() || done.get() || (cancel != null && cancel.isCancelled())) {
            done.set(true);
            disposable.dispose();
        }
        ref.set(disposable);
        if (cancel != null) {
            // 取消可能发生在订阅之前或期间：early 动作已置位，这里补一次 dispose。
            if (cancel.isCancelled()) {
                done.set(true);
                disposable.dispose();
            }
        }
        future.whenComplete((value, error) -> {
            if (future.isCancelled() || (cancel != null && cancel.isCancelled())) {
                done.set(true);
                Disposable current = ref.get();
                if (current != null) {
                    current.dispose();
                }
            }
            if (cancel != null) {
                cancel.bindAbort(null);
            }
        });
    }

    private boolean openOrFail(CompletableFuture<?> future) {
        if (!closed.get()) {
            return true;
        }
        future.completeExceptionally(new LlmCallException("WebClient HTTP 执行器已关闭", null,
                LlmCallException.Kind.CONFIG));
        return false;
    }

    private static boolean cancelOrFail(CompletableFuture<?> future, LlmCancelHandle cancel) {
        if (cancel == null || !cancel.isCancelled()) {
            return false;
        }
        future.completeExceptionally(cancelled(null));
        return true;
    }

    private static Throwable mapError(Throwable error, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return cancelled(error);
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof LlmCallException) {
                return current;
            }
            if (current instanceof CancellationException) {
                return cancelled(current);
            }
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return new LlmCallException("模型请求超时", error,
                        LlmCallException.Kind.TRANSPORT);
            }
            if (current instanceof IOException) {
                return new LlmCallException("模型请求失败", error,
                        LlmCallException.Kind.TRANSPORT);
            }
            current = current.getCause();
        }
        return new LlmCallException("模型请求失败", error,
                LlmCallException.Kind.TRANSPORT);
    }

    private static LlmCallException streamFailure(Throwable error, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return cancelled(error);
        }
        if (error instanceof LlmCallException) {
            return (LlmCallException) error;
        }
        return new LlmCallException("流式响应处理失败", error,
                LlmCallException.Kind.PARSE);
    }

    private static LlmCallException cancelled(Throwable cause) {
        return new LlmCallException("调用已取消", cause,
                LlmCallException.Kind.CANCELLED);
    }

    private static LlmCallException httpError(int status, String body, String retryAfterHeader) {
        return LlmCallException.httpError("模型请求失败，状态码：" + status + brief(body),
                status, retryAfterHeader);
    }

    private static RuntimeException unwrap(Throwable error) {
        if (error instanceof LlmCallException) {
            return (LlmCallException) error;
        }
        if (error instanceof RuntimeException) {
            return (RuntimeException) error;
        }
        return new LlmCallException("模型请求失败", error,
                LlmCallException.Kind.TRANSPORT);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 300) {
            oneLine = oneLine.substring(0, 300);
        }
        return oneLine.isEmpty() ? "" : "，响应：" + oneLine;
    }
}

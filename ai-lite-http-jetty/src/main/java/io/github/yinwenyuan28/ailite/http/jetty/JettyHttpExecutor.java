package io.github.yinwenyuan28.ailite.http.jetty;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.http.Utf8LineBuffer;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jetty 9.4 NIO 传输。
 *
 * <p>一个连接池承载大量在途请求，等待响应不占调用线程。同步入口仍按契约阻塞
 * 调用线程；异步入口由 Jetty selector 线程驱动 {@code Response.Listener}。</p>
 *
 * <p>SSE 只负责把原始字节切成行，事件拼装仍在 core 的协议适配器里。因此这里
 * 不做 SSE 插件式解析，空行、{@code data:}、{@code [DONE]} 都原样交给上层。</p>
 *
 * <p>Jetty 的 connect timeout 是客户端级配置，而 {@link HttpRequest} 是每请求
 * 携带的。这里按 connect timeout 缓存客户端；组合数上限 {@value #MAX_CLIENTS}，
 * 避免异常配置导致线程/selector 泄漏。</p>
 */
public final class JettyHttpExecutor implements AsyncHttpExecutor {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final int MAX_CLIENTS = 16;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 10000L;
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final AiLiteConfig.Http config;
    private final ConcurrentMap<Integer, HttpClient> clients =
            new ConcurrentHashMap<Integer, HttpClient>();
    private final Object clientLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param config HTTP 配置，可为 null
     */
    public JettyHttpExecutor(AiLiteConfig.Http config) {
        this.config = config == null ? new AiLiteConfig.Http() : config;
    }

    /**
     * 供 Starter 反射调用的工厂。
     *
     * @param config HTTP 配置
     * @return 执行器
     */
    public static HttpExecutor create(AiLiteConfig.Http config) {
        return new JettyHttpExecutor(config);
    }

    @Override
    public HttpResult execute(HttpRequest request) {
        ensureOpen();
        checkCancelled(request.getCancel());
        Request jettyRequest = newRequest(request, false);
        try {
            ContentResponse response = jettyRequest.send();
            return toResult(response.getStatus(),
                    new String(response.getContent(), UTF_8), response.getHeaders().get("Retry-After"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure(ex, request.getCancel());
        } catch (TimeoutException ex) {
            throw failure(ex, request.getCancel());
        } catch (ExecutionException ex) {
            throw failure(ex.getCause(), request.getCancel());
        } catch (LlmCallException ex) {
            throw ex;
        } finally {
            unbind(request.getCancel());
        }
    }

    @Override
    public void executeStreaming(HttpRequest request, StreamListener listener) {
        ensureOpen();
        checkCancelled(request.getCancel());
        Request jettyRequest = newRequest(request, true);
        InputStreamResponseListener responseListener = new InputStreamResponseListener();
        try {
            jettyRequest.send(responseListener);
            Response response = responseListener.get(headerTimeout(request), TimeUnit.MILLISECONDS);
            InputStream input = responseListener.getInputStream();
            try {
                if (!isSuccessful(response.getStatus())) {
                    throw httpError(response.getStatus(),
                            readBounded(input, MAX_ERROR_BYTES),
                            response.getHeaders().get("Retry-After"));
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancelled(request.getCancel());
                    listener.onLine(line);
                }
            } finally {
                closeQuietly(input);
            }
        } catch (LlmCallException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            abortQuietly(jettyRequest, ex);
            throw failure(ex, request.getCancel());
        } catch (TimeoutException ex) {
            abortQuietly(jettyRequest, ex);
            throw failure(ex, request.getCancel());
        } catch (ExecutionException ex) {
            throw failure(ex.getCause(), request.getCancel());
        } catch (IOException ex) {
            abortQuietly(jettyRequest, ex);
            throw failure(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    @Override
    public CompletableFuture<HttpResult> executeAsync(HttpRequest request) {
        final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        final Request jettyRequest;
        try {
            jettyRequest = newRequest(request, false);
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        final AtomicInteger status = new AtomicInteger(-1);
        final AtomicReference<String> retryAfter = new AtomicReference<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicBoolean done = new AtomicBoolean(false);
        jettyRequest.send(new Response.Listener.Adapter() {
            @Override
            public void onBegin(Response response) {
                status.set(response.getStatus());
            }

            @Override
            public void onHeaders(Response response) {
                retryAfter.set(response.getHeaders().get("Retry-After"));
            }

            @Override
            public void onContent(Response response, ByteBuffer content) {
                if (isSuccessful(response.getStatus())) {
                    append(content, body, 0);
                } else {
                    append(content, body, MAX_ERROR_BYTES);
                }
            }

            @Override
            public void onComplete(Result result) {
                unbind(request.getCancel());
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                if (result.isFailed()) {
                    future.completeExceptionally(failure(result.getFailure(), request.getCancel()));
                    return;
                }
                int code = status.get();
                String text = new String(body.toByteArray(), UTF_8);
                if (!isSuccessful(code)) {
                    future.completeExceptionally(httpError(code, text, retryAfter.get()));
                    return;
                }
                future.complete(new HttpResult(code, text));
            }
        });
        abortOnCancel(future, jettyRequest);
        return future;
    }

    @Override
    public CompletableFuture<Void> executeStreamingAsync(final HttpRequest request,
                                                         final StreamListener listener) {
        final CompletableFuture<Void> future = new CompletableFuture<Void>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        final Request jettyRequest;
        try {
            jettyRequest = newRequest(request, true);
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        final AtomicInteger status = new AtomicInteger(-1);
        final AtomicReference<String> retryAfter = new AtomicReference<String>();
        final Utf8LineBuffer lines = new Utf8LineBuffer(MAX_LINE_BYTES);
        final ByteArrayOutputStream errorBody = new ByteArrayOutputStream();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicBoolean done = new AtomicBoolean(false);
        jettyRequest.send(new Response.Listener.Adapter() {
            @Override
            public void onBegin(Response response) {
                status.set(response.getStatus());
            }

            @Override
            public void onHeaders(Response response) {
                retryAfter.set(response.getHeaders().get("Retry-After"));
            }

            @Override
            public void onContent(Response response, ByteBuffer content) {
                if (!isSuccessful(response.getStatus())) {
                    append(content, errorBody, MAX_ERROR_BYTES);
                    return;
                }
                if (failed.get()) {
                    return;
                }
                byte[] bytes = new byte[content.remaining()];
                content.get(bytes);
                try {
                    for (String line : lines.accept(bytes)) {
                        if (failed.get()) {
                            return;
                        }
                        listener.onLine(line);
                    }
                } catch (Throwable ex) {
                    failed.set(true);
                    future.completeExceptionally(streamFailure(ex, request.getCancel()));
                    jettyRequest.abort(ex);
                }
            }

            @Override
            public void onComplete(Result result) {
                unbind(request.getCancel());
                if (!done.compareAndSet(false, true) || future.isDone()) {
                    return;
                }
                if (result.isFailed()) {
                    future.completeExceptionally(failure(result.getFailure(), request.getCancel()));
                    return;
                }
                int code = status.get();
                if (!isSuccessful(code)) {
                    future.completeExceptionally(httpError(code,
                            new String(errorBody.toByteArray(), UTF_8), retryAfter.get()));
                    return;
                }
                try {
                    String tail = lines.flush();
                    if (tail != null) {
                        listener.onLine(tail);
                    }
                    future.complete(null);
                } catch (Throwable ex) {
                    future.completeExceptionally(streamFailure(ex, request.getCancel()));
                }
            }
        });
        abortOnCancel(future, jettyRequest);
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<HttpClient> snapshot = new ArrayList<HttpClient>(clients.values());
        clients.clear();
        for (HttpClient client : snapshot) {
            stopQuietly(client);
        }
    }

    /**
     * 构造请求。connect timeout 由客户端负责；读空闲用 request.idleTimeout，
     * 不设置总时长，避免把长 SSE 误杀。
     */
    private Request newRequest(HttpRequest request, boolean streaming) {
        HttpClient client = client(request);
        Request jettyRequest = client.newRequest(request.getUrl())
                .method(HttpMethod.POST)
                // Jetty 9.4 的三参构造器顺序是 (contentType, content, charset)。
                .content(new StringContentProvider(CONTENT_TYPE, request.getBody(), UTF_8))
                .followRedirects(false);
        if (request.getReadTimeoutMs() > 0) {
            jettyRequest.idleTimeout(request.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            // Request.header 是追加语义，会与 Jetty 默认头重复；这里改用覆盖。
            jettyRequest.getHeaders().put(header.getKey(), header.getValue());
        }
        // SSE 不能走 gzip 缓冲；HttpClient 构造时已放入 gzip，必须覆盖而不是追加。
        if (!request.getHeaders().containsKey("Accept-Encoding")) {
            jettyRequest.getHeaders().put("Accept-Encoding", "identity");
        }
        bind(jettyRequest, request.getCancel());
        return jettyRequest;
    }

    /**
     * 按 connect timeout 取客户端；同一超时组合共享 selector 和连接池。
     */
    private HttpClient client(HttpRequest request) {
        int timeout = request.getConnectTimeoutMs() > 0
                ? request.getConnectTimeoutMs() : (int) DEFAULT_CONNECT_TIMEOUT_MS;
        Integer key = Integer.valueOf(timeout);
        HttpClient existing = clients.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (clientLock) {
            existing = clients.get(key);
            if (existing != null) {
                return existing;
            }
            if (closed.get()) {
                throw new LlmCallException("Jetty HTTP 执行器已关闭", null,
                        LlmCallException.Kind.CONFIG);
            }
            if (clients.size() >= MAX_CLIENTS) {
                throw new LlmCallException("Jetty connect timeout 组合超过 "
                        + MAX_CLIENTS + " 个，请统一供应商的超时配置", null,
                        LlmCallException.Kind.CONFIG);
            }
            HttpClient created = createClient(timeout);
            clients.put(key, created);
            return created;
        }
    }

    private HttpClient createClient(int connectTimeoutMs) {
        HttpClient client = new HttpClient();
        client.setConnectTimeout(connectTimeoutMs);
        client.setFollowRedirects(false);
        client.setIdleTimeout(0);
        Integer maxConnections = config.getJetty().getMaxConnectionsPerDestination();
        if (maxConnections != null) {
            if (maxConnections.intValue() <= 0) {
                throw new LlmCallException(
                        "ai-lite.http.jetty.max-connections-per-destination 必须大于 0",
                        null, LlmCallException.Kind.CONFIG);
            }
            client.setMaxConnectionsPerDestination(maxConnections.intValue());
        }
        Integer threads = config.getJetty().getThreads();
        if (threads != null && threads.intValue() > 0) {
            QueuedThreadPool pool = new QueuedThreadPool(threads.intValue(), threads.intValue());
            pool.setName("ai-lite-jetty-" + connectTimeoutMs);
            client.setExecutor(pool);
        }
        try {
            client.start();
            return client;
        } catch (Exception ex) {
            stopQuietly(client);
            throw new LlmCallException("Jetty HTTP 客户端启动失败", ex,
                    LlmCallException.Kind.CONFIG);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new LlmCallException("Jetty HTTP 执行器已关闭", null,
                    LlmCallException.Kind.CONFIG);
        }
    }

    private boolean openOrFail(CompletableFuture<?> future) {
        if (!closed.get()) {
            return true;
        }
        future.completeExceptionally(new LlmCallException("Jetty HTTP 执行器已关闭", null,
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

    private static void checkCancelled(LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            throw cancelled(null);
        }
    }

    private static void bind(final Request request, LlmCancelHandle cancel) {
        if (cancel == null) {
            return;
        }
        cancel.bindAbort(new Runnable() {
            @Override
            public void run() {
                request.abort(new CancellationException("调用已取消"));
            }
        });
    }

    private static void unbind(LlmCancelHandle cancel) {
        if (cancel != null) {
            cancel.bindAbort(null);
        }
    }

    /**
     * future.cancel(true) 不是网关的取消句柄，直接对调用方暴露的 future 也要 abort。
     */
    private static void abortOnCancel(final CompletableFuture<?> future, final Request request) {
        future.whenComplete((value, error) -> {
            if (future.isCancelled()) {
                request.abort(new CancellationException("调用已取消"));
            }
        });
    }

    private static void append(ByteBuffer content, ByteArrayOutputStream out, int limit) {
        int remaining = content.remaining();
        if (remaining <= 0) {
            return;
        }
        int allowed = limit <= 0 ? remaining : Math.min(remaining, limit - out.size());
        if (allowed <= 0) {
            return;
        }
        byte[] bytes = new byte[allowed];
        content.get(bytes, 0, allowed);
        out.write(bytes, 0, allowed);
    }

    private static HttpResult toResult(int status, String body, String retryAfterHeader) {
        if (!isSuccessful(status)) {
            throw httpError(status, body, retryAfterHeader);
        }
        return new HttpResult(status, body);
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static LlmCallException httpError(int status, String body, String retryAfterHeader) {
        return LlmCallException.httpError("模型请求失败，状态码：" + status + brief(body),
                status, retryAfterHeader);
    }

    private static LlmCallException failure(Throwable error, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return cancelled(error);
        }
        if (error instanceof CancellationException) {
            return cancelled(error);
        }
        if (error instanceof EOFException || (error != null
                && error.getClass().getSimpleName().toLowerCase().contains("eof"))) {
            return new LlmCallException("模型响应提前结束", error,
                    LlmCallException.Kind.TRANSPORT);
        }
        if (error instanceof TimeoutException) {
            return new LlmCallException("模型请求超时", error,
                    LlmCallException.Kind.TRANSPORT);
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

    private static long headerTimeout(HttpRequest request) {
        return request.getReadTimeoutMs() > 0
                ? request.getReadTimeoutMs() : Long.MAX_VALUE;
    }

    private static String readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (out.size() < limit) {
            int max = Math.min(buffer.length, limit - out.size());
            int read = input.read(buffer, 0, max);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), UTF_8);
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 关闭失败不覆盖原始异常。
        }
    }

    private static void abortQuietly(Request request, Throwable cause) {
        try {
            request.abort(cause);
        } catch (RuntimeException ignored) {
            // 请求已经结束时 abort 是 no-op；不要覆盖原始异常。
        }
    }

    private static void stopQuietly(HttpClient client) {
        try {
            client.stop();
        } catch (Exception ignored) {
            // close 幂等，不向调用方抛生命周期异常。
        }
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

package io.github.yinwenyuan28.ailite.http.jdk8;

import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.http.BoundedLineReader;
import io.github.yinwenyuan28.ailite.internal.http.Timeouts;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 零依赖实现，基于 HttpURLConnection。
 *
 * <p>适合不能引入 OkHttp 的老项目。连接池能力弱于 OkHttp，但行为足够覆盖
 * Chat Completions、Responses 和 Anthropic Messages 的阻塞与 SSE 调用。</p>
 *
 * <p>取消有两条独立入口，都必须能中断在途连接：网关的 {@link LlmCancelHandle}
 * （绑定在 {@link #open} 里）与调用方直接对 future 的 {@code cancel(true)}
 * （绑定在 {@link #executeAsync} 的完成回调里）。只剩后者的实现会让 future 显示
 * 已取消、连接却继续跑到读超时——这正是 F-21 修掉的问题。</p>
 */
public final class Jdk8HttpExecutor implements AsyncHttpExecutor, RawHttpExecutor {

    /** 单行字节上限：与异步路径的 {@code Utf8LineBuffer} 保持一致。 */
    private static final int MAX_LINE_BYTES = 1024 * 1024;

    /** 非流式成功响应体上限：超出按响应损坏处理，避免打爆堆。 */
    private static final int MAX_SUCCESS_BYTES = 8 * 1024 * 1024;

    /** 非 2xx 错误体上限：错误摘要只需要开头一小段。 */
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    private final Executor executor;
    private final java.util.concurrent.ExecutorService ownedExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 使用懒创建的守护线程池执行异步调用。
     */
    public Jdk8HttpExecutor() {
        this.ownedExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
        this.executor = ownedExecutor;
    }

    /**
     * @param executor 异步调用使用的线程池；由调用方负责关闭
     */
    public Jdk8HttpExecutor(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Executor 不能为空");
        }
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /**
     * 异步执行。future 被取消时断开在途连接，而不是只改 future 状态。
     */
    @Override
    public CompletableFuture<HttpResult> executeAsync(final HttpRequest request) {
        final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        final InFlight inFlight = new InFlight();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        future.complete(execute(request, inFlight));
                    } catch (Throwable t) {
                        // 异步入口的契约是「返回 future」，异常必须收敛进 future 而不是
                        // 抛给线程池（否则会被池吞掉，调用方永远等不到结果）。
                        if (!future.isDone()) {
                            future.completeExceptionally(mapError(t, request.getCancel()));
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        abortOnCancel(future, inFlight);
        return future;
    }

    /**
     * 异步流式执行。取消同样断开在途连接。
     */
    @Override
    public CompletableFuture<Void> executeStreamingAsync(final HttpRequest request,
                                                         final StreamListener listener) {
        final CompletableFuture<Void> future = new CompletableFuture<Void>();
        if (!openOrFail(future) || cancelOrFail(future, request.getCancel())) {
            return future;
        }
        final InFlight inFlight = new InFlight();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        executeStreaming(request, listener, inFlight);
                        future.complete(null);
                    } catch (Throwable t) {
                        if (!future.isDone()) {
                            future.completeExceptionally(mapError(t, request.getCancel()));
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        abortOnCancel(future, inFlight);
        return future;
    }

    @Override
    public HttpResult execute(HttpRequest request) {
        ensureOpen();
        return execute(request, null);
    }

    /**
     * 诊断用：非 2xx 也返回状态码与完整正文。
     *
     * <p>直接复用 {@link #open}，因此与 {@link #execute} 的请求头、超时、
     * 取消绑定完全一致；也与其余五种实现一致地<b>不跟随 3xx</b>——管理台试跑
     * 要看到的是原始响应，而不是重定向后的终点。</p>
     */
    @Override
    public HttpResult executeRaw(HttpRequest request) {
        ensureOpen();
        HttpURLConnection connection = null;
        try {
            connection = open(request, null);
            int status = connection.getResponseCode();
            // 与 execute 的唯一区别：错误流也当正常结果读回，不抛异常。
            InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(status, readAll(stream, MAX_SUCCESS_BYTES));
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            disconnect(connection);
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    @Override
    public void executeStreaming(HttpRequest request, StreamListener listener) {
        ensureOpen();
        executeStreaming(request, listener, null);
    }

    /**
     * @param inFlight 异步路径的在途连接载体；同步路径传 null
     */
    private void executeStreaming(HttpRequest request, StreamListener listener, InFlight inFlight) {
        HttpURLConnection connection = null;
        BoundedLineReader reader = null;
        try {
            connection = open(request, inFlight);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error(connection, status);
            }
            reader = new BoundedLineReader(connection.getInputStream(), MAX_LINE_BYTES);
            String line;
            while ((line = reader.next()) != null) {
                if (request.getCancel() != null && request.getCancel().isCancelled()) {
                    throw new LlmCallException("调用已取消", null,
                            LlmCallException.Kind.CANCELLED);
                }
                listener.onLine(line);
            }
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            disconnect(connection);
            throw wrap(ex, request.getCancel());
        } finally {
            closeQuietly(reader);
            unbind(request.getCancel());
            if (inFlight != null) {
                inFlight.detach();
            }
        }
    }

    /**
     * @param inFlight 异步路径的在途连接载体；同步路径传 null
     */
    private HttpResult execute(HttpRequest request, InFlight inFlight) {
        HttpURLConnection connection = null;
        try {
            connection = open(request, inFlight);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error(connection, status);
            }
            return new HttpResult(status, readAll(connection.getInputStream(), MAX_SUCCESS_BYTES));
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            disconnect(connection);
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
            if (inFlight != null) {
                inFlight.detach();
            }
        }
    }

    private HttpURLConnection open(HttpRequest request, InFlight inFlight) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.getUrl()).openConnection();
        connection.setRequestMethod(request.getMethod());
        connection.setConnectTimeout(Timeouts.connect(request));
        connection.setReadTimeout(Timeouts.read(request));
        // 管理台「试跑」要看到原始 3xx；HttpURLConnection 默认自动跟随。
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        final HttpURLConnection bound = connection;
        LlmCancelHandle cancel = request.getCancel();
        if (cancel != null) {
            cancel.bindAbort(new Runnable() {
                @Override
                public void run() {
                    bound.disconnect();
                }
            });
            if (cancel.isCancelled()) {
                connection.disconnect();
                throw new LlmCallException("调用已取消", null,
                        LlmCallException.Kind.CANCELLED);
            }
        }
        if (inFlight != null) {
            inFlight.attach(connection);
        }
        // GET 不能写 body：HttpURLConnection 会把它当 POST，或因 doOutput 改变语义。
        if (!request.isGet()) {
            connection.setDoOutput(true);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(request.getBody().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                output.close();
            }
        }
        return connection;
    }

    private static String readAll(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (out.size() + read > maxBytes) {
                    throw new LlmCallException("响应体超过 " + maxBytes + " 字节", null,
                            LlmCallException.Kind.PARSE);
                }
                out.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static LlmCallException error(HttpURLConnection connection, int status) {
        String body = "";
        try {
            body = readCapped(connection.getErrorStream(), MAX_ERROR_BYTES);
        } catch (IOException ignored) {
            // 错误体读不到时只保留状态码。
        }
        return LlmCallException.httpError("模型请求失败，状态码：" + status + brief(body),
                status, connection.getHeaderField("Retry-After"));
    }

    /**
     * 只读前 {@code maxBytes} 字节，超出部分丢弃。
     *
     * <p>错误体只用来凑异常摘要，读不下不该把「服务端返回了 500」变成「响应损坏」。</p>
     */
    private static String readCapped(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096];
            while (out.size() < maxBytes) {
                int max = Math.min(buffer.length, maxBytes - out.size());
                int read = input.read(buffer, 0, max);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 300) {
            oneLine = oneLine.substring(0, 300);
        }
        return oneLine.isEmpty() ? "" : "，响应：" + oneLine;
    }

    private static LlmCallException wrap(IOException ex, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        }
        return new LlmCallException("模型请求失败", ex, LlmCallException.Kind.TRANSPORT);
    }

    /**
     * 把任务体抛出的任何异常收敛成 {@link LlmCallException}，保留取消语义。
     */
    private static LlmCallException mapError(Throwable error, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return new LlmCallException("调用已取消", error, LlmCallException.Kind.CANCELLED);
        }
        if (error instanceof LlmCallException) {
            return (LlmCallException) error;
        }
        if (error instanceof java.util.concurrent.CancellationException) {
            return new LlmCallException("调用已取消", error, LlmCallException.Kind.CANCELLED);
        }
        return new LlmCallException("模型请求失败", error, LlmCallException.Kind.TRANSPORT);
    }

    /**
     * 正常完成时只解绑取消动作，不能 disconnect；否则会关闭底层 socket，
     * HttpURLConnection 的 keep-alive 连接池永远无法复用。取消/异常路径才断开。
     */
    private static void unbind(LlmCancelHandle cancel) {
        if (cancel != null) {
            cancel.bindAbort(null);
        }
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }

    /**
     * future 被取消时断开在途连接。{@code HttpURLConnection.disconnect()} 能唤醒
     * 阻塞中的「等响应头」；读正文阶段不保证立刻返回，但至少不再完全无效。
     */
    private static void abortOnCancel(final CompletableFuture<?> future, final InFlight inFlight) {
        future.whenComplete((value, error) -> {
            if (future.isCancelled()) {
                inFlight.abort();
            }
        });
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new LlmCallException("JDK8 HTTP 执行器已关闭", null,
                    LlmCallException.Kind.CONFIG);
        }
    }

    private boolean openOrFail(CompletableFuture<?> future) {
        if (!closed.get()) {
            return true;
        }
        future.completeExceptionally(new LlmCallException("JDK8 HTTP 执行器已关闭", null,
                LlmCallException.Kind.CONFIG));
        return false;
    }

    private static boolean cancelOrFail(CompletableFuture<?> future, LlmCancelHandle cancel) {
        if (cancel == null || !cancel.isCancelled()) {
            return false;
        }
        future.completeExceptionally(new LlmCallException("调用已取消", null,
                LlmCallException.Kind.CANCELLED));
        return true;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 关闭失败不覆盖主流程异常。
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    /**
     * 异步路径的在途连接载体。
     *
     * <p>与 {@link LlmCancelHandle} 的绑定并存而不是替代：句柄负责「网关取消」，
     * 这里负责「调用方直接取消 future」。{@code getAndSet(null)} 保证连接只被
     * 断开一次，且 {@link #detach} 之后迟到的 abort 不会误伤后续请求。</p>
     */
    private static final class InFlight {

        private final AtomicReference<HttpURLConnection> connection =
                new AtomicReference<HttpURLConnection>();

        void attach(HttpURLConnection value) {
            connection.set(value);
        }

        void detach() {
            connection.set(null);
        }

        void abort() {
            HttpURLConnection current = connection.getAndSet(null);
            if (current != null) {
                current.disconnect();
            }
        }
    }

    /**
     * 守护线程命名，便于线程 dump。
     */
    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-lite-jdk8-http-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

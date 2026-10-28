package io.github.yinwenyuan28.ailite.http.jdk8;

import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 零依赖实现，基于 HttpURLConnection。
 *
 * <p>适合不能引入 OkHttp 的老项目。连接池能力弱于 OkHttp，但行为足够覆盖
 * Chat Completions、Responses 和 Anthropic Messages 的阻塞与 SSE 调用。</p>
 */
public final class Jdk8HttpExecutor implements AsyncHttpExecutor, RawHttpExecutor {

    private final Executor executor;
    private final java.util.concurrent.ExecutorService ownedExecutor;

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

    @Override
    public CompletableFuture<HttpResult> executeAsync(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request), executor);
    }

    @Override
    public CompletableFuture<Void> executeStreamingAsync(HttpRequest request, StreamListener listener) {
        return CompletableFuture.runAsync(() -> executeStreaming(request, listener), executor);
    }

    @Override
    public HttpResult execute(HttpRequest request) {
        HttpURLConnection connection = null;
        try {
            connection = open(request);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error(connection, status);
            }
            return new HttpResult(status, readAll(connection.getInputStream()));
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            disconnect(connection);
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    /**
     * 诊断用：非 2xx 也返回状态码与完整正文。
     *
     * <p>直接复用 {@link #open}，因此与 {@link #execute} 的请求头、超时、
     * 取消绑定完全一致。</p>
     */
    @Override
    public HttpResult executeRaw(HttpRequest request) {
        HttpURLConnection connection = null;
        try {
            connection = open(request);
            int status = connection.getResponseCode();
            // 与 execute 的唯一区别：错误流也当正常结果读回，不抛异常。
            InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(status, readAll(stream));
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
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            connection = open(request);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw error(connection, status);
            }
            reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
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
        }
    }

    private static HttpURLConnection open(HttpRequest request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.getUrl()).openConnection();
        connection.setRequestMethod(request.getMethod());
        connection.setConnectTimeout(request.getConnectTimeoutMs());
        connection.setReadTimeout(request.getReadTimeoutMs());
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

    private static String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
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
            body = readAll(connection.getErrorStream());
        } catch (IOException ignored) {
            // 错误体读不到时只保留状态码。
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 300) {
            oneLine = oneLine.substring(0, 300);
        }
        return LlmCallException.httpError("模型请求失败，状态码：" + status
                + (oneLine.isEmpty() ? "" : "，响应：" + oneLine),
                status, connection.getHeaderField("Retry-After"));
    }

    private static LlmCallException wrap(IOException ex, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        }
        return new LlmCallException("模型请求失败", ex, LlmCallException.Kind.TRANSPORT);
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
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
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

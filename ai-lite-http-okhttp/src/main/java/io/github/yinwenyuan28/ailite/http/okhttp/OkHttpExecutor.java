package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.http.BoundedLineReader;
import io.github.yinwenyuan28.ailite.internal.http.Timeouts;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OkHttp 实现（API 选用 3.x/4.x 都有的签名，便于宿主 classpath 仍是 OkHttp 3.8 时跑通）。
 *
 * <p>所有请求共用一个连接池和 dispatcher；每次调用只在 newBuilder 上覆盖超时，
 * 不会为一次请求新建整套线程和连接资源。</p>
 *
 * <p>超时统一走 {@link Timeouts}：{@code ≤0} 取框架默认值，而不是 OkHttp 的
 * 「0 = 无限等待」。六个实现若各自解释 0，同一份配置会得到六种行为。</p>
 */
public final class OkHttpExecutor implements AsyncHttpExecutor, RawHttpExecutor {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 单行字节上限：与异步路径的行缓冲保持一致。 */
    private static final int MAX_LINE_BYTES = 1024 * 1024;

    /** 非流式成功响应体上限。 */
    private static final int MAX_SUCCESS_BYTES = 8 * 1024 * 1024;

    /** 非 2xx 错误体上限：错误摘要只需要开头一小段。 */
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    private final OkHttpClient shared;
    /** true 表示 Dispatcher 线程池由本执行器创建，close 时才允许关闭。 */
    private final boolean ownsDispatcher;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建默认执行器。关闭连接重试，避免和网关自己的重试策略叠加。
     */
    public OkHttpExecutor() {
        this(new OkHttpClient.Builder().retryOnConnectionFailure(false).build(), true);
    }

    /**
     * 使用调用方提供的客户端。
     *
     * <p>本执行器<b>不会</b>关闭传入的 dispatcher 与连接池——它们的生命周期归调用方。
     * 需要「close 时一并关闭」时用两参构造器并显式传 {@code ownsDispatcher=true}。</p>
     *
     * @param shared 共享客户端；调用方负责其连接池配置与关闭
     */
    public OkHttpExecutor(OkHttpClient shared) {
        this(shared, false);
    }

    /**
     * @param shared          客户端
     * @param ownsDispatcher  true 时 close 会关闭 Dispatcher 线程池；用户传入的客户端传 false
     */
    public OkHttpExecutor(OkHttpClient shared, boolean ownsDispatcher) {
        if (shared == null) {
            throw new LlmCallException("OkHttpClient 不能为空", null,
                    LlmCallException.Kind.CONFIG);
        }
        this.shared = shared;
        this.ownsDispatcher = ownsDispatcher;
    }

    @Override
    public HttpResult execute(HttpRequest request) {
        ensureOpen();
        Call call = buildCall(request, false);
        try {
            Response response = call.execute();
            try {
                ensureSuccessful(response);
                ResponseBody body = response.body();
                return new HttpResult(response.code(), body == null ? ""
                        : readBounded(body.byteStream(), MAX_SUCCESS_BYTES));
            } finally {
                response.close();
            }
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    /**
     * 诊断用：非 2xx 也返回状态码与完整正文。
     *
     * <p>与 {@link #execute} 共用同一个 {@link OkHttpClient}（同一连接池、同一
     * dispatcher），试跑看到的成败因此与真实模型调用一致；也一致地不跟随 3xx。</p>
     */
    @Override
    public HttpResult executeRaw(HttpRequest request) {
        ensureOpen();
        Call call = buildCall(request, false);
        try {
            Response response = call.execute();
            try {
                // 与 execute 的唯一区别：不校验状态码，4xx/5xx 原样交回。
                ResponseBody body = response.body();
                return new HttpResult(response.code(), body == null ? ""
                        : readBounded(body.byteStream(), MAX_SUCCESS_BYTES));
            } finally {
                response.close();
            }
        } catch (IOException ex) {
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    @Override
    public void executeStreaming(final HttpRequest request, final StreamListener listener) {
        ensureOpen();
        Call call = buildCall(request, true);
        try {
            Response response = call.execute();
            try {
                ensureSuccessful(response);
                ResponseBody body = response.body();
                if (body == null) {
                    throw new LlmCallException("响应无法解析", null,
                            LlmCallException.Kind.PARSE);
                }
                BoundedLineReader reader = new BoundedLineReader(body.byteStream(), MAX_LINE_BYTES);
                try {
                    String line;
                    while ((line = reader.next()) != null) {
                        if (request.getCancel() != null && request.getCancel().isCancelled()) {
                            throw new LlmCallException("调用已取消", null,
                                    LlmCallException.Kind.CANCELLED);
                        }
                        listener.onLine(line);
                    }
                } finally {
                    reader.close();
                }
            } finally {
                response.close();
            }
        } catch (LlmCallException ex) {
            throw ex;
        } catch (IOException ex) {
            throw wrap(ex, request.getCancel());
        } finally {
            unbind(request.getCancel());
        }
    }

    @Override
    public CompletableFuture<HttpResult> executeAsync(final HttpRequest request) {
        final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
        if (!openOrFail(future)) {
            return future;
        }
        final Call call;
        try {
            call = buildCall(request, false);
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException ex) {
                unbind(request.getCancel());
                completeFailure(future, ex, request.getCancel());
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try {
                    if (future.isCancelled()) {
                        return;
                    }
                    ensureSuccessful(response);
                    ResponseBody body = response.body();
                    future.complete(new HttpResult(response.code(), body == null ? ""
                            : readBounded(body.byteStream(), MAX_SUCCESS_BYTES)));
                } catch (LlmCallException ex) {
                    future.completeExceptionally(ex);
                } catch (IOException ex) {
                    completeFailure(future, ex, request.getCancel());
                } catch (RuntimeException ex) {
                    future.completeExceptionally(ex);
                } finally {
                    response.close();
                    unbind(request.getCancel());
                }
            }
        });
        cancelCallWithFuture(future, call);
        return future;
    }

    @Override
    public CompletableFuture<Void> executeStreamingAsync(final HttpRequest request,
                                                         final StreamListener listener) {
        final CompletableFuture<Void> future = new CompletableFuture<Void>();
        if (!openOrFail(future)) {
            return future;
        }
        final Call call;
        try {
            call = buildCall(request, true);
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException ex) {
                unbind(request.getCancel());
                completeFailure(future, ex, request.getCancel());
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try {
                    if (future.isCancelled()) {
                        return;
                    }
                    ensureSuccessful(response);
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new LlmCallException("响应无法解析", null,
                                LlmCallException.Kind.PARSE);
                    }
                    BoundedLineReader reader =
                            new BoundedLineReader(body.byteStream(), MAX_LINE_BYTES);
                    try {
                        String line;
                        while ((line = reader.next()) != null) {
                            if (request.getCancel() != null
                                    && request.getCancel().isCancelled()) {
                                throw new LlmCallException("调用已取消", null,
                                        LlmCallException.Kind.CANCELLED);
                            }
                            listener.onLine(line);
                        }
                    } finally {
                        reader.close();
                    }
                    future.complete(null);
                } catch (LlmCallException ex) {
                    future.completeExceptionally(ex);
                } catch (IOException ex) {
                    completeFailure(future, ex, request.getCancel());
                } catch (RuntimeException ex) {
                    future.completeExceptionally(ex);
                } finally {
                    response.close();
                    unbind(request.getCancel());
                }
            }
        });
        cancelCallWithFuture(future, call);
        return future;
    }

    /**
     * 调用方取消 future 时同步取消底层 Call，避免连接继续挂着。
     */
    private static void cancelCallWithFuture(final CompletableFuture<?> future, final Call call) {
        future.whenComplete((value, error) -> {
            if (future.isCancelled()) {
                call.cancel();
            }
        });
    }

    private static void completeFailure(CompletableFuture<?> future, IOException ex,
                                        LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            future.completeExceptionally(new LlmCallException("调用已取消", ex,
                    LlmCallException.Kind.CANCELLED));
            return;
        }
        if (future.isCancelled()) {
            future.completeExceptionally(new CancellationException("调用已取消"));
            return;
        }
        future.completeExceptionally(new LlmCallException("模型请求失败", ex,
                LlmCallException.Kind.TRANSPORT));
    }

    /**
     * @param streaming 流式请求必须禁用压缩：gzip 解压缓冲会破坏「字节到达即逐行交付」
     */
    private Call buildCall(HttpRequest request, boolean streaming) {
        OkHttpClient client = shared.newBuilder()
                // 管理台「试跑」要看到原始 3xx；OkHttp 默认自动跟随重定向。
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Timeouts.connect(request), TimeUnit.MILLISECONDS)
                .readTimeout(Timeouts.read(request), TimeUnit.MILLISECONDS)
                .writeTimeout(Timeouts.read(request), TimeUnit.MILLISECONDS)
                .build();
        Request.Builder builder = new Request.Builder().url(request.getUrl());
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        // OkHttp 的 transparentGzip 只在调用方没显式设 Accept-Encoding 时生效；
        // 设了 identity 就不会自动加 gzip，也不会解压，逐行语义得以保留。
        if (streaming && !request.getHeaders().containsKey("Accept-Encoding")) {
            builder.header("Accept-Encoding", "identity");
        }
        // GET 不能带 body；OkHttp 会对带 body 的 GET 抛异常。
        builder.method(request.getMethod(), request.isGet()
                ? null : jsonBody(request.getBody()));
        Call call = client.newCall(builder.build());
        bind(request.getCancel(), call);
        return call;
    }

    /**
     * 参数顺序是 create(类型, 正文)。OkHttp 3.8 只有这一种；4.12 也还保留。
     * create(正文, 类型) 是 4.x 才有的，在 3.8 上会 NoSuchMethodError。
     */
    private static RequestBody jsonBody(String body) {
        return RequestBody.create(JSON, body == null ? "" : body);
    }

    private static void ensureSuccessful(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        ResponseBody body = response.body();
        String text = body == null ? "" : readCapped(body.byteStream(), MAX_ERROR_BYTES);
        throw LlmCallException.httpError("模型请求失败，状态码：" + response.code() + brief(text),
                response.code(), response.header("Retry-After"));
    }

    /**
     * 有界读取：{@code body.string()} 会把整个响应体读进内存再截断摘要，超长响应能
     * 直接打爆堆。这里按上限读，超出即按响应损坏处理。
     */
    private static String readBounded(InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (out.size() + read > maxBytes) {
                throw new LlmCallException("响应体超过 " + maxBytes + " 字节", null,
                        LlmCallException.Kind.PARSE);
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 只读前 {@code maxBytes} 字节，超出部分直接丢弃。
     *
     * <p>用于错误体：这里的目标是给异常消息凑一段摘要，读不下不该把「服务端返回了
     * 500」变成「响应损坏」——那会让调用方拿到更少的分类信息。</p>
     */
    private static String readCapped(InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (out.size() < maxBytes) {
            int max = Math.min(buffer.length, maxBytes - out.size());
            int read = input.read(buffer, 0, max);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
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

    private static LlmCallException wrap(IOException ex, LlmCancelHandle cancel) {
        if (cancel != null && cancel.isCancelled()) {
            return new LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED);
        }
        return new LlmCallException("模型请求失败", ex, LlmCallException.Kind.TRANSPORT);
    }

    private static void bind(LlmCancelHandle cancel, final Call call) {
        if (cancel == null) {
            return;
        }
        cancel.bindAbort(new Runnable() {
            @Override
            public void run() {
                call.cancel();
            }
        });
        if (cancel.isCancelled()) {
            call.cancel();
        }
    }

    private static void unbind(LlmCancelHandle cancel) {
        if (cancel != null) {
            cancel.bindAbort(null);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new LlmCallException("OkHttp HTTP 执行器已关闭", null,
                    LlmCallException.Kind.CONFIG);
        }
    }

    private boolean openOrFail(CompletableFuture<?> future) {
        if (!closed.get()) {
            return true;
        }
        future.completeExceptionally(new LlmCallException("OkHttp HTTP 执行器已关闭", null,
                LlmCallException.Kind.CONFIG));
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownsDispatcher) {
            shared.dispatcher().executorService().shutdown();
            shared.connectionPool().evictAll();
        }
    }
}

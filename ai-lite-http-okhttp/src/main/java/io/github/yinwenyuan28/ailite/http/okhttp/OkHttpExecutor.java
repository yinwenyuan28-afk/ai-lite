package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.StreamListener;
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
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 实现（API 选用 3.x/4.x 都有的签名，便于宿主 classpath 仍是 OkHttp 3.8 时跑通）。
 *
 * <p>所有请求共用一个连接池和 dispatcher；每次调用只在 newBuilder 上覆盖超时，
 * 不会为一次请求新建整套线程和连接资源。</p>
 */
public final class OkHttpExecutor implements AsyncHttpExecutor, RawHttpExecutor {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient shared;
    /** true 表示 Dispatcher 线程池由本执行器创建，close 时才允许关闭。 */
    private final boolean ownsDispatcher;

    /**
     * 创建默认执行器。关闭连接重试，避免和网关自己的重试策略叠加。
     */
    public OkHttpExecutor() {
        this(new OkHttpClient.Builder().retryOnConnectionFailure(false).build(), true);
    }

    /**
     * @param shared 共享客户端；调用方负责其连接池配置
     */
    public OkHttpExecutor(OkHttpClient shared) {
        this(shared, true);
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
        Call call = buildCall(request);
        try {
            Response response = call.execute();
            try {
                ensureSuccessful(response);
                ResponseBody body = response.body();
                return new HttpResult(response.code(), body == null ? "" : body.string());
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
     * dispatcher），试跑看到的成败因此与真实模型调用一致。</p>
     */
    @Override
    public HttpResult executeRaw(HttpRequest request) {
        Call call = buildCall(request);
        try {
            Response response = call.execute();
            try {
                // 与 execute 的唯一区别：不校验状态码，4xx/5xx 原样交回。
                ResponseBody body = response.body();
                return new HttpResult(response.code(), body == null ? "" : body.string());
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
        Call call = buildCall(request);
        try {
            Response response = call.execute();
            try {
                ensureSuccessful(response);
                ResponseBody body = response.body();
                if (body == null) {
                    throw new LlmCallException("响应无法解析", null,
                            LlmCallException.Kind.PARSE);
                }
                BufferedSource source = body.source();
                String line;
                while ((line = source.readUtf8Line()) != null) {
                    if (request.getCancel() != null && request.getCancel().isCancelled()) {
                        throw new LlmCallException("调用已取消", null,
                                LlmCallException.Kind.CANCELLED);
                    }
                    listener.onLine(line);
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
        final Call call = buildCall(request);
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
                    future.complete(new HttpResult(response.code(),
                            body == null ? "" : body.string()));
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
        final Call call = buildCall(request);
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
                    BufferedSource source = body.source();
                    String line;
                    while ((line = source.readUtf8Line()) != null) {
                        if (request.getCancel() != null && request.getCancel().isCancelled()) {
                            throw new LlmCallException("调用已取消", null,
                                    LlmCallException.Kind.CANCELLED);
                        }
                        listener.onLine(line);
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

    private Call buildCall(HttpRequest request) {
        OkHttpClient client = shared.newBuilder()
                .connectTimeout(request.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(request.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(request.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        Request.Builder builder = new Request.Builder().url(request.getUrl());
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
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
        String text = body == null ? "" : body.string();
        throw LlmCallException.httpError("模型请求失败，状态码：" + response.code() + brief(text),
                response.code(), response.header("Retry-After"));
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

    @Override
    public void close() {
        if (ownsDispatcher) {
            shared.dispatcher().executorService().shutdown();
            shared.connectionPool().evictAll();
        }
    }
}

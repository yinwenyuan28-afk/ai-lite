package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 4.x 实现。
 *
 * <p>所有请求共用一个连接池和 dispatcher；每次调用只在 newBuilder 上覆盖超时，
 * 不会为一次请求新建整套线程和连接资源。</p>
 */
public final class OkHttpExecutor implements HttpExecutor {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient shared;

    /**
     * 创建默认执行器。关闭连接重试，避免和网关自己的重试策略叠加。
     */
    public OkHttpExecutor() {
        this(new OkHttpClient.Builder().retryOnConnectionFailure(false).build());
    }

    /**
     * @param shared 共享客户端；调用方负责其连接池配置
     */
    public OkHttpExecutor(OkHttpClient shared) {
        if (shared == null) {
            throw new LlmCallException("OkHttpClient 不能为空");
        }
        this.shared = shared;
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

    @Override
    public void executeStreaming(final HttpRequest request, final StreamListener listener) {
        Call call = buildCall(request);
        try {
            Response response = call.execute();
            try {
                ensureSuccessful(response);
                ResponseBody body = response.body();
                if (body == null) {
                    throw new LlmCallException("响应无法解析");
                }
                BufferedSource source = body.source();
                String line;
                while ((line = source.readUtf8Line()) != null) {
                    if (request.getCancel() != null && request.getCancel().isCancelled()) {
                        throw new LlmCallException("调用已取消");
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
        builder.post(RequestBody.create(request.getBody(), JSON));
        Call call = client.newCall(builder.build());
        bind(request.getCancel(), call);
        return call;
    }

    private static void ensureSuccessful(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        ResponseBody body = response.body();
        String text = body == null ? "" : body.string();
        throw new LlmCallException("模型请求失败，状态码：" + response.code() + brief(text));
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
            return new LlmCallException("调用已取消", ex);
        }
        return new LlmCallException("模型请求失败", ex);
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
        shared.dispatcher().executorService().shutdown();
        shared.connectionPool().evictAll();
    }
}

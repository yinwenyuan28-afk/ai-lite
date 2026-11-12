package io.github.yinwenyuan28.ailite.internal.http;

import io.github.yinwenyuan28.ailite.http.HttpRequest;

/**
 * 把「非正值表示未配置」归一成具体毫秒数。
 *
 * <p>同一个 {@code timeouts(0, 0)} 在六个 HTTP 客户端里的含义并不相同：OkHttp 的
 * {@code readTimeout(0)} 是无限等待，Jetty 的 {@code idleTimeout(0)} 也是无限，
 * Ktor 干脆不设超时，WebClient 则退回 Netty 默认值。如果让每个实现自己解释，
 * 同一份配置会得到六种行为，「切换传输」就等于无声地改变超时语义。</p>
 *
 * <p>因此在这一层统一成一条规则：<b>≤0 一律取框架默认值</b>。默认值取自
 * {@code AiLiteConfig.Provider} 的既有默认（连接 10 秒、读取 120 秒），不是新拍的
 * 数字；这样「没配」与「显式配 0」得到同一个结果，符合 {@link HttpRequest}
 * 里「非正值由传输实现取默认」的原意。</p>
 */
public final class Timeouts {

    /** 默认连接超时：10 秒，与供应商配置的默认值一致。 */
    public static final int DEFAULT_CONNECT_MS = 10_000;

    /** 默认读取超时：120 秒，与供应商配置的默认值一致。 */
    public static final int DEFAULT_READ_MS = 120_000;

    private Timeouts() {
    }

    /**
     * @param request 请求，不能为 null
     * @return 实际使用的连接超时，恒为正
     */
    public static int connect(HttpRequest request) {
        int value = request.getConnectTimeoutMs();
        return value > 0 ? value : DEFAULT_CONNECT_MS;
    }

    /**
     * @param request 请求，不能为 null
     * @return 实际使用的读取超时，恒为正
     */
    public static int read(HttpRequest request) {
        int value = request.getReadTimeoutMs();
        return value > 0 ? value : DEFAULT_READ_MS;
    }
}

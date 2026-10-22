package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 启动时报告实际使用的 HTTP 传输，并提示被忽略的配置。
 *
 * <p>只读诊断，不参与装配；用户自定义 {@link HttpExecutor} 时也能看到覆盖结果。</p>
 */
final class HttpTransportReporter implements ApplicationListener<ContextRefreshedEvent> {

    private static final Log LOG = LogFactory.getLog(HttpTransportReporter.class);

    private static final Map<String, String> EXPECTED_TYPES = new LinkedHashMap<String, String>();

    static {
        EXPECTED_TYPES.put("okhttp",
                "io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor");
        EXPECTED_TYPES.put("okhttp-virtual",
                "io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor");
        EXPECTED_TYPES.put("jetty",
                "io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor");
        EXPECTED_TYPES.put("webclient",
                "io.github.yinwenyuan28.ailite.http.webclient.WebClientHttpExecutor");
        EXPECTED_TYPES.put("ktor-cio",
                "io.github.yinwenyuan28.ailite.http.ktor.KtorCioHttpExecutor");
        EXPECTED_TYPES.put("jdk8",
                "io.github.yinwenyuan28.ailite.http.jdk8.Jdk8HttpExecutor");
    }

    private final AiLiteConfig config;
    private final ApplicationContext context;
    private volatile boolean reported;

    HttpTransportReporter(AiLiteConfig config, ApplicationContext context) {
        this.config = config;
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != context || reported) {
            return;
        }
        reported = true;
        String transport = normalize(config.getHttp().getTransport());
        Map<String, HttpExecutor> beans = context.getBeansOfType(HttpExecutor.class);
        if (beans.isEmpty()) {
            LOG.warn("AI Lite 没有 HttpExecutor Bean，模型调用会在第一次请求时失败");
            return;
        }
        Map.Entry<String, HttpExecutor> entry = beans.entrySet().iterator().next();
        Class<?> actual = entry.getValue().getClass();
        String expected = EXPECTED_TYPES.get(transport);
        if (expected != null && !expected.equals(actual.getName())) {
            LOG.warn("AI Lite HTTP 传输被自定义 Bean 覆盖：transport=" + transport
                    + "，实际=" + actual.getName() + "（bean=" + entry.getKey() + "）");
        } else {
            LOG.info("AI Lite HTTP 传输：transport=" + transport
                    + "，实现=" + actual.getName());
        }
        warnIgnoredConfig(transport);
    }

    /**
     * 与当前 transport 无关的显式配置很容易让用户误以为生效，这里统一提示。
     */
    private void warnIgnoredConfig(String transport) {
        boolean okhttp = "okhttp".equals(transport) || "okhttp-virtual".equals(transport);
        if (!okhttp && (config.getHttp().getDispatcher().getMaxRequests() != null
                || config.getHttp().getDispatcher().getMaxRequestsPerHost() != null)) {
            LOG.warn("ai-lite.http.dispatcher.* 只对 okhttp/okhttp-virtual 生效，当前 transport="
                    + transport);
        }
        if (!"jetty".equals(transport)
                && (config.getHttp().getJetty().getMaxConnectionsPerDestination() != null
                || config.getHttp().getJetty().getThreads() != null)) {
            LOG.warn("ai-lite.http.jetty.* 只对 jetty 生效，当前 transport=" + transport);
        }
        if (!"ktor-cio".equals(transport)
                && (config.getHttp().getKtor().getMaxConnections() != null
                || config.getHttp().getKtor().getMaxConnectionsPerRoute() != null
                || config.getHttp().getKtor().getPipelining() != null)) {
            LOG.warn("ai-lite.http.ktor.* 只对 ktor-cio 生效，当前 transport=" + transport);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "okhttp";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

package io.github.yinwenyuan28.ailite.benchmark.fake;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.Locale;

/**
 * 可独立启动的假模型服务。
 *
 * <p>目的是让压测不依赖真实供应商和密钥。它只做一件事：按请求体里的
 * {@code bench-*} 标记，返回正常响应、SSE 分片、深嵌套 JSON、错误码、
 * 中途断开或挂住不响应。行为清单见 {@link FakeBehavior}。</p>
 *
 * <p>用法：
 * <pre>
 * mvn -q -f benchmark/fake-llm-server/pom.xml compile exec:java \
 *     -Dexec.args="--port=18181 --threads=256"
 * </pre>
 * 启动后把 {@code http://127.0.0.1:18181/v1} 当作供应商的 base-url。
 * 支持 {@code /chat/completions}、{@code /responses}、{@code /v1/messages}、{@code /models}。</p>
 */
public final class FakeLlmMain {

    private FakeLlmMain() {
    }

    /**
     * @param args 支持 {@code --port=}、{@code --threads=}；未知参数会被忽略
     */
    public static void main(String[] args) throws Exception {
        int port = (int) option(args, "--port", 18181L);
        int threads = (int) option(args, "--threads", 256L);

        QueuedThreadPool pool = new QueuedThreadPool(Math.max(8, threads));
        pool.setName("fake-llm");
        Server server = new Server(pool);
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        connector.setAcceptQueueSize(4096);
        server.addConnector(connector);

        FakeHandler.Counter counter = new FakeHandler.Counter();
        server.setHandler(new FakeHandler(counter));
        server.start();

        int actual = connector.getLocalPort();
        System.out.println("fake LLM listening on http://127.0.0.1:" + actual + "/v1");
        System.out.println("threads=" + threads);
        System.out.println("paths: POST /chat/completions, POST /responses, POST /v1/messages, GET /models");
        System.out.println("behaviour markers (put them in request body): "
                + "bench-hold=<seconds> bench-chunks=<n> bench-chunk-size=<n> bench-stream=true "
                + "bench-status=<code> bench-retry-after=<v> bench-deep=<depth> "
                + "bench-tool-calls=<n> bench-disconnect=true bench-hang-headers=true "
                + "bench-no-usage=true");

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("fake LLM stopping: total=" + counter.total.get()
                        + " finished=" + counter.finished.get()
                        + " streamed=" + counter.streamed.get()
                        + " errored=" + counter.errored.get());
            }
        }, "fake-llm-shutdown"));

        server.join();
    }

    /**
     * @param args    命令行参数
     * @param name    参数名，形如 {@code --port}
     * @param fallback 缺失或非法时的默认值
     * @return 解析出的数值
     */
    private static long option(String[] args, String name, long fallback) {
        if (args == null) {
            return fallback;
        }
        String prefix = name + "=";
        for (String arg : args) {
            if (arg == null || !arg.startsWith(prefix)) {
                continue;
            }
            try {
                long value = Long.parseLong(arg.substring(prefix.length()).trim());
                return value > 0L ? value : fallback;
            } catch (NumberFormatException ex) {
                System.out.println("ignoring bad value for " + name + ": "
                        + arg.toLowerCase(Locale.ROOT));
                return fallback;
            }
        }
        return fallback;
    }
}

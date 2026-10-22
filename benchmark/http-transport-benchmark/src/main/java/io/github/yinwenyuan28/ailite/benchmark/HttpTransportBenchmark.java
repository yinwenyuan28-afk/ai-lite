package io.github.yinwenyuan28.ailite.benchmark;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.jdk8.Jdk8HttpExecutor;
import io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor;
import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpClientFactory;
import io.github.yinwenyuan28.ailite.http.webclient.WebClientHttpExecutor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 fake LLM 服务 + HTTP 传输并发基准。
 *
 * <p>用法（先在仓库根目录 {@code mvn install -DskipTests}）：</p>
 *
 * <pre>
 * cd benchmark/http-transport-benchmark
 * mvn -q exec:java -Dexec.args="--transports=okhttp,jetty,webclient,ktor-cio,jdk8"
 * </pre>
 *
 * <p>每个场景用 {@code concurrency} 条独立链，每条链串行发请求，因此在途请求数
 * 恰好等于并发度；不会用“每请求一个平台线程”的负载方式污染被测传输的线程模型。</p>
 */
public final class HttpTransportBenchmark {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private HttpTransportBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        FakeLlmServer server = new FakeLlmServer(config);
        server.start();
        System.out.println("# AI Lite HTTP transport benchmark");
        System.out.println();
        System.out.println("fake server threads: " + config.serverThreads
                + ", delay: " + config.delayMs + "ms, stream chunks: "
                + config.streamChunks + "x" + config.streamDelayMs + "ms");
        System.out.println();
        System.out.println("| transport | mode | concurrency | requests | ok | err | req/s | p50 ms | p95 ms | p99 ms | peak threads | transport threads | heap MB |");
        System.out.println("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        try {
            int maxConcurrency = 1;
            for (Integer concurrency : config.concurrencies) {
                maxConcurrency = Math.max(maxConcurrency, concurrency.intValue());
            }
            for (String transport : config.transports) {
                HttpExecutor executor;
                try {
                    // 每个 transport 只建一次执行器，所有场景共享连接池，避免反复
                    // 建连把本地临时端口耗尽。
                    executor = create(transport, maxConcurrency);
                } catch (Throwable ex) {
                    for (String mode : config.modes) {
                        for (Integer concurrency : config.concurrencies) {
                            System.out.println(Row.unavailable(transport, mode,
                                    concurrency.intValue(), ex).line());
                        }
                    }
                    System.out.flush();
                    continue;
                }
                try {
                    for (String mode : config.modes) {
                        for (Integer concurrency : config.concurrencies) {
                            System.out.println(run(transport, mode, concurrency.intValue(),
                                    config, server, executor).line());
                            System.out.flush();
                        }
                    }
                } finally {
                    executor.close();
                }
            }
        } finally {
            server.stop();
        }
    }

    private static Row run(String transport, String mode, int concurrency,
                           Config config, FakeLlmServer server, HttpExecutor executor) {
        try {
            HttpRequest request = request(server.url(), mode);
            // 预热：每个并发链各发一次，建立连接和线程池。
            runLoad((AsyncHttpExecutor) executor, request, mode, concurrency, 1, null, null, null, null);
            int rounds = Math.max(1, config.requests / concurrency);
            int measured = rounds * concurrency;
            Latencies latencies = new Latencies();
            AtomicLong ok = new AtomicLong();
            AtomicLong errors = new AtomicLong();
            ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<String>();
            Sampler sampler = new Sampler(transport);
            sampler.start();
            long start = System.nanoTime();
            runLoad((AsyncHttpExecutor) executor, request, mode, concurrency, rounds,
                    latencies, ok, errors, errorSamples);
            long elapsedNanos = System.nanoTime() - start;
            sampler.stop();

            if (!errorSamples.isEmpty()) {
                System.err.println("! " + transport + "/" + mode + "/" + concurrency
                        + " errors: " + errorSamples);
            }
            long[] values = latencies.sorted();
            return new Row(transport, mode, concurrency, measured,
                    ok.get(), errors.get(),
                    measured / (elapsedNanos / 1_000_000_000.0),
                    percentile(values, 0.50), percentile(values, 0.95),
                    percentile(values, 0.99),
                    sampler.peakTotal.get(), sampler.peakTransport.get(),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                            / (1024L * 1024L));
        } catch (Throwable ex) {
            return Row.unavailable(transport, mode, concurrency, ex);
        }
    }

    /**
     * 用 {@code concurrency} 条链各执行 {@code rounds} 次；链内串行，链间并行。
     */
    private static void runLoad(AsyncHttpExecutor executor, HttpRequest request, String mode,
                                int concurrency, int rounds, Latencies latencies,
                                AtomicLong ok, AtomicLong errors,
                                final ConcurrentLinkedQueue<String> errorSamples) throws Exception {
        List<CompletableFuture<Void>> chains = new ArrayList<CompletableFuture<Void>>(concurrency);
        for (int chainIndex = 0; chainIndex < concurrency; chainIndex++) {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int round = 0; round < rounds; round++) {
                chain = chain.thenCompose(ignored ->
                        one(executor, request, mode, latencies, ok, errors, errorSamples));
            }
            chains.add(chain);
        }
        CompletableFuture.allOf(chains.toArray(new CompletableFuture[chains.size()]))
                .get(120, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Void> one(AsyncHttpExecutor executor, HttpRequest request,
                                               String mode, final Latencies latencies,
                                               final AtomicLong ok, final AtomicLong errors,
                                               final ConcurrentLinkedQueue<String> errorSamples) {
        final long start = System.nanoTime();
        CompletableFuture<?> future = "stream".equals(mode)
                ? executor.executeStreamingAsync(request, line -> {
                })
                : executor.executeAsync(request);
        return future.handle((value, error) -> {
            if (latencies != null) {
                latencies.add(System.nanoTime() - start);
            }
            if (error != null) {
                if (errors != null) {
                    errors.incrementAndGet();
                }
                if (errorSamples != null && errorSamples.size() < 5) {
                    errorSamples.add(describe(error));
                }
            } else if (ok != null) {
                ok.incrementAndGet();
            }
            return (Void) null;
        });
    }

    private static HttpExecutor create(String transport, int concurrency) throws Exception {
        AiLiteConfig.Http config = new AiLiteConfig.Http();
        config.getDispatcher().setMaxRequests(Integer.valueOf(concurrency));
        config.getDispatcher().setMaxRequestsPerHost(Integer.valueOf(concurrency));
        config.getJetty().setMaxConnectionsPerDestination(Integer.valueOf(concurrency));
        config.getKtor().setMaxConnections(Integer.valueOf(concurrency));
        config.getKtor().setMaxConnectionsPerRoute(Integer.valueOf(concurrency));

        if ("okhttp".equals(transport)) {
            return OkHttpClientFactory.executor(config);
        }
        if ("okhttp-virtual".equals(transport)) {
            return (HttpExecutor) loadFactory(
                    "io.github.yinwenyuan28.ailite.http.okhttp.jdk21.VirtualThreadOkHttpFactory",
                    config);
        }
        if ("jetty".equals(transport)) {
            return JettyHttpExecutor.create(config);
        }
        if ("webclient".equals(transport)) {
            return WebClientHttpExecutor.create(config);
        }
        if ("ktor-cio".equals(transport)) {
            return (HttpExecutor) loadFactory(
                    "io.github.yinwenyuan28.ailite.http.ktor.KtorCioHttpExecutor", config);
        }
        if ("jdk8".equals(transport)) {
            return new Jdk8HttpExecutor();
        }
        throw new IllegalArgumentException("unknown transport: " + transport);
    }

    private static Object loadFactory(String className, AiLiteConfig.Http config) throws Exception {
        Class<?> type = Class.forName(className);
        return type.getMethod("create", AiLiteConfig.Http.class).invoke(null, config);
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message != null && message.length() > 160) {
            message = message.substring(0, 160);
        }
        return current.getClass().getSimpleName() + ": " + message;
    }

    private static HttpRequest request(String url, String mode) {
        String body = "stream".equals(mode) ? "{\"stream\":true}" : "{\"stream\":false}";
        return new HttpRequest(url,
                Collections.singletonMap("Content-Type", "application/json; charset=utf-8"),
                body, 10000, 120000, null);
    }

    private static double percentile(long[] sorted, double ratio) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(sorted.length * ratio) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index] / 1_000_000.0;
    }

    /**
     * 采样平台线程峰值。虚拟线程不会出现在 Thread.getAllStackTraces 里，因此
     * okhttp-virtual 的 transport threads 会显示 0，这是预期行为。
     */
    private static final class Sampler {

        private final String transport;
        private final AtomicInteger peakTotal = new AtomicInteger();
        private final AtomicInteger peakTransport = new AtomicInteger();
        private volatile boolean running = true;
        private Thread thread;

        private Sampler(String transport) {
            this.transport = transport;
        }

        private void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
                        peakTotal.accumulateAndGet(all.size(), Math::max);
                        int matched = 0;
                        for (Thread current : all.keySet()) {
                            if (matches(transport, current.getName())) {
                                matched++;
                            }
                        }
                        peakTransport.accumulateAndGet(matched, Math::max);
                        sleep(25L);
                    }
                }
            }, "benchmark-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void stop() {
            running = false;
            try {
                thread.join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (Boolean.getBoolean("benchmark.dumpThreads")) {
                for (Thread current : Thread.getAllStackTraces().keySet()) {
                    System.err.println("THREAD " + current.getName());
                }
            }
        }

        private static boolean matches(String transport, String name) {
            if ("okhttp".equals(transport)) {
                return name.contains("OkHttp");
            }
            if ("okhttp-virtual".equals(transport)) {
                return name.contains("ai-lite-http-virtual");
            }
            if ("jetty".equals(transport)) {
                // Jetty 9.4 的 HttpClient 默认线程名是 HttpClient@<hash>-<n>。
                return name.contains("qtp") || name.contains("jetty")
                        || name.contains("HttpClient@")
                        || name.contains("ai-lite-jetty");
            }
            if ("webclient".equals(transport)) {
                return name.contains("reactor") || name.contains("webflux-http");
            }
            if ("ktor-cio".equals(transport)) {
                return name.contains("ktor") || name.contains("DefaultDispatcher-worker");
            }
            if ("jdk8".equals(transport)) {
                return name.contains("pool-") || name.contains("http-jdk8")
                        || name.contains("ai-lite-jdk8");
            }
            return false;
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Latencies {

        private final ConcurrentLinkedQueue<Long> values = new ConcurrentLinkedQueue<Long>();

        private void add(long nanos) {
            values.add(Long.valueOf(nanos));
        }

        private long[] sorted() {
            long[] result = new long[values.size()];
            int index = 0;
            for (Long value : values) {
                result[index++] = value.longValue();
            }
            java.util.Arrays.sort(result);
            return result;
        }
    }

    private static final class Row {

        private final String transport;
        private final String mode;
        private final int concurrency;
        private final int requests;
        private final long ok;
        private final long errors;
        private final double throughput;
        private final double p50;
        private final double p95;
        private final double p99;
        private final int peakThreads;
        private final int transportThreads;
        private final long heapMb;
        private final String unavailable;

        private Row(String transport, String mode, int concurrency, int requests, long ok,
                    long errors, double throughput, double p50, double p95, double p99,
                    int peakThreads, int transportThreads, long heapMb) {
            this.transport = transport;
            this.mode = mode;
            this.concurrency = concurrency;
            this.requests = requests;
            this.ok = ok;
            this.errors = errors;
            this.throughput = throughput;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.peakThreads = peakThreads;
            this.transportThreads = transportThreads;
            this.heapMb = heapMb;
            this.unavailable = null;
        }

        private Row(String transport, String mode, int concurrency, String unavailable) {
            this.transport = transport;
            this.mode = mode;
            this.concurrency = concurrency;
            this.requests = 0;
            this.ok = 0;
            this.errors = 0;
            this.throughput = 0;
            this.p50 = 0;
            this.p95 = 0;
            this.p99 = 0;
            this.peakThreads = 0;
            this.transportThreads = 0;
            this.heapMb = 0;
            this.unavailable = unavailable;
        }

        private static Row unavailable(String transport, String mode, int concurrency,
                                       Throwable error) {
            String message = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (message.length() > 120) {
                message = message.substring(0, 120);
            }
            return new Row(transport, mode, concurrency, message);
        }

        private String line() {
            if (unavailable != null) {
                return String.format(Locale.ROOT,
                        "| %s | %s | %d | - | - | - | - | - | - | - | - | - | %s |",
                        transport, mode, Integer.valueOf(concurrency), sanitize(unavailable));
            }
            return String.format(Locale.ROOT,
                    "| %s | %s | %d | %d | %d | %d | %.1f | %.2f | %.2f | %.2f | %d | %d | %d |",
                    transport, mode, Integer.valueOf(concurrency), Integer.valueOf(requests),
                    Long.valueOf(ok), Long.valueOf(errors), Double.valueOf(throughput),
                    Double.valueOf(p50), Double.valueOf(p95), Double.valueOf(p99),
                    Integer.valueOf(peakThreads), Integer.valueOf(transportThreads),
                    Long.valueOf(heapMb));
        }

        private static String sanitize(String text) {
            return text == null ? "" : text.replace('|', '/');
        }
    }

    private static final class Config {

        private final List<String> transports = new ArrayList<String>();
        private final List<String> modes = new ArrayList<String>();
        private final List<Integer> concurrencies = new ArrayList<Integer>();
        private int requests = 2000;
        private int delayMs = 50;
        private int streamChunks = 8;
        private int streamDelayMs = 5;
        private int serverThreads = 256;

        private static Config parse(String[] args) {
            Config config = new Config();
            config.transports.addAll(split("okhttp,okhttp-virtual,jetty,webclient,ktor-cio,jdk8"));
            config.modes.addAll(split("nonstream,stream"));
            for (String value : split("100,500,1000")) {
                config.concurrencies.add(Integer.valueOf(value));
            }
            for (String arg : args) {
                if (arg.startsWith("--transports=")) {
                    config.transports.clear();
                    config.transports.addAll(split(arg.substring("--transports=".length())));
                } else if (arg.startsWith("--modes=")) {
                    config.modes.clear();
                    config.modes.addAll(split(arg.substring("--modes=".length())));
                } else if (arg.startsWith("--concurrency=")) {
                    config.concurrencies.clear();
                    for (String value : split(arg.substring("--concurrency=".length()))) {
                        config.concurrencies.add(Integer.valueOf(value));
                    }
                } else if (arg.startsWith("--requests=")) {
                    config.requests = Integer.parseInt(arg.substring("--requests=".length()));
                } else if (arg.startsWith("--delay-ms=")) {
                    config.delayMs = Integer.parseInt(arg.substring("--delay-ms=".length()));
                } else if (arg.startsWith("--stream-chunks=")) {
                    config.streamChunks = Integer.parseInt(arg.substring("--stream-chunks=".length()));
                } else if (arg.startsWith("--stream-delay-ms=")) {
                    config.streamDelayMs = Integer.parseInt(arg.substring("--stream-delay-ms=".length()));
                } else if (arg.startsWith("--server-threads=")) {
                    config.serverThreads = Integer.parseInt(arg.substring("--server-threads=".length()));
                }
            }
            return config;
        }

        private static List<String> split(String value) {
            List<String> result = new ArrayList<String>();
            for (String item : value.split(",")) {
                if (!item.trim().isEmpty()) {
                    result.add(item.trim());
                }
            }
            return result;
        }
    }

    /**
     * 最小 fake LLM 服务：非流式返回固定 JSON，流式返回 SSE。
     */
    private static final class FakeLlmServer {

        private final Config config;
        private Server server;
        private ServerConnector connector;

        private FakeLlmServer(Config config) {
            this.config = config;
        }

        private void start() throws Exception {
            QueuedThreadPool pool = new QueuedThreadPool(config.serverThreads);
            pool.setName("fake-llm");
            server = new Server(pool);
            connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            connector.setAcceptQueueSize(4096);
            server.addConnector(connector);
            server.setHandler(new Handler(config));
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + connector.getLocalPort() + "/v1/chat/completions";
        }

        private void stop() throws Exception {
            if (server != null) {
                server.stop();
                server.join();
            }
        }
    }

    private static final class Handler extends AbstractHandler {

        private final Config config;

        private Handler(Config config) {
            this.config = config;
        }

        @Override
        public void handle(String target, Request baseRequest,
                           HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            try {
                String body = readAll(request.getInputStream());
                if (body.contains("\"stream\":true")) {
                    stream(response);
                } else {
                    nonStream(response);
                }
                baseRequest.setHandled(true);
            } catch (IOException ex) {
                if (!response.isCommitted()) {
                    response.sendError(500);
                }
            }
        }

        private void nonStream(HttpServletResponse response) throws IOException {
            sleep(config.delayMs);
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                    .getBytes(UTF_8);
            response.setStatus(200);
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
        }

        private void stream(HttpServletResponse response) throws IOException {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            ServletOutputStream output = response.getOutputStream();
            sleep(config.delayMs);
            for (int i = 0; i < config.streamChunks; i++) {
                output.write(("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                        .getBytes(UTF_8));
                output.flush();
                sleep(config.streamDelayMs);
            }
            output.write("data: [DONE]\n\n".getBytes(UTF_8));
            output.flush();
        }

        private static String readAll(InputStream input) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF_8);
        }

        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

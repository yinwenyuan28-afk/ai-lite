package io.github.yinwenyuan28.ailite.benchmark.fake;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor;
import io.github.yinwenyuan28.ailite.internal.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存探针：用真实的网关和 Jetty 传输打本机假服务，观测堆与线程是否异常增长。
 *
 * <p>为什么要有这个东西：单元测试只验证「行为对不对」，验证不了「连续跑一万次
 * 之后堆还稳不稳」。生产环境是 JDK 8 + Jetty 传输 + 长连接，泄漏往往表现为
 * 「跑几天之后 Full GC 变频繁」，用一次性的用例抓不住。</p>
 *
 * <p>探针在每个场景前后各取一次堆快照，算出增量。判据不是「绝对值多少 MB」，
 * 而是「做完 GC 之后还剩下多少」——真正泄漏的对象活过年轻代，所以看老年代
 * 与存活集才有意义。</p>
 */
public final class MemoryProbeMain {

    private static final String DEFAULT_BASE = "http://127.0.0.1:18181/v1";

    private MemoryProbeMain() {
    }

    /**
     * @param args 支持 {@code --base=}、{@code --rounds=}、{@code --concurrency=}
     */
    public static void main(String[] args) throws Exception {
        String base = option(args, "--base", DEFAULT_BASE);
        int rounds = (int) number(args, "--rounds", 200L);
        int concurrency = (int) number(args, "--concurrency", 32L);

        System.out.println("=== 内存探针 ===");
        System.out.println("假服务: " + base);
        System.out.println("每场景轮数: " + rounds + "，并发: " + concurrency);
        System.out.printf("JVM: %s, 最大堆 %d MB%n",
                System.getProperty("java.version"), maxHeapMb());

        AiLiteConfig.Http http = new AiLiteConfig.Http();
        http.setTransport("jetty");
        AiLiteConfig.Jetty jetty = new AiLiteConfig.Jetty();
        // 与 A104527 生产一致：每目标 200 个连接。
        jetty.setMaxConnectionsPerDestination(Integer.valueOf(200));
        // 故意不设 threads：用 Jetty 默认池，这是最接近「用户什么都不配」的情形。
        http.setJetty(jetty);

        JettyHttpExecutor executor = new JettyHttpExecutor(http);
        AiLiteConfig.Executor execCfg = new AiLiteConfig.Executor();
        execCfg.setCoreSize(4);
        execCfg.setMaxSize(16);
        execCfg.setQueueCapacity(4096);
        execCfg.setThreadNamePrefix("probe-io-");
        PlatformExecutorProvider provider = new PlatformExecutorProvider(execCfg);

        LlmGateway gateway = new LlmGateway(
                resolver(base),
                Collections.singletonList(new OpenAiCompletionsTransport(executor)),
                new NoopLlmCallStore(), provider, new SequentialToolExecutor());

        try {
            // 同一场景连跑两遍：第一遍让 Jetty 把它的一次性线程池/连接池扩到位，
            // 第二遍才是真正的稳态。两遍的增量放在一起看，就能区分
            // 「一次性扩容」和「每次调用都漏一个」——后者两遍增量会一样大。
            scenario("正常非流式 #1（含线程池首次扩容）", gateway, rounds, concurrency, "hi");
            scenario("正常非流式 #2（稳态对照）", gateway, rounds, concurrency, "hi");
            scenario("正常流式分片 20 条", gateway, rounds, concurrency,
                    "bench-chunks=20", Entry.STREAM);
            scenario("大分片流式 16×64KB #1", gateway, rounds, concurrency,
                    "bench-chunks=16 bench-chunk-size=65536", Entry.STREAM);
            // 大分片连跑第二遍：第一遍的净增长若是缓冲区容量留存，第二遍应接近 0；
            // 若两遍都涨，就是每次调用都有东西活过 GC。
            scenario("大分片流式 16×64KB #2（对照）", gateway, rounds, concurrency,
                    "bench-chunks=16 bench-chunk-size=65536", Entry.STREAM);
            // 单个响应 4MB（64 条 × 64KB）：量化「整个响应体在堆里留几份」。
            // transport 会把每行再 append 进 raw 累积器，哪怕根本用不上。
            scenario("单个响应 4MB 流式", gateway, Math.max(1, rounds / 20), concurrency,
                    "bench-chunks=64 bench-chunk-size=65536", Entry.STREAM);
            scenario("深嵌套 JSON 2000 层（非流式）", gateway, rounds, concurrency,
                    "bench-deep=2000");
            scenario("深嵌套 JSON 20000 层（探边界）", gateway, rounds, concurrency,
                    "bench-deep=20000");
            // 流式路径的深嵌套单独压：onContent 里是直接调 listener.onLine，
            // 没有 future 兜底，收敛行为与非流式不同。
            scenario("深嵌套 JSON 20000 层（流式）", gateway, rounds, concurrency,
                    "bench-deep=20000 bench-chunks=2", Entry.STREAM);
            scenario("429 带 Retry-After（不重试，只压错误路径）", gateway, rounds, concurrency,
                    "bench-status=429 bench-retry-after=1");
            scenario("429 带 Retry-After（走显式重试）", gateway, rounds, concurrency,
                    "bench-status=429 bench-retry-after=1", Entry.RETRY);
            scenario("响应提前断开", gateway, rounds, concurrency, "bench-disconnect=true");
            // A2：SSE 事件永不结束（只发 data 行、不发空行）。
            // SseCollector.data 只在空行时清空，所以这个场景专门压它的累积上限。
            scenario("SSE 事件永不结束（压 SseCollector 累积）", gateway,
                    Math.max(1, rounds / 20), 1,
                    "bench-never-end=true bench-chunks=2000 bench-chunk-size=1024 bench-line-delay=1",
                    Entry.STREAM);
            scenario("稳态复跑（全部故障场景之后）", gateway, rounds, concurrency, "hi");
        } finally {
            gateway.shutdown();
            executor.close();
            provider.close();
        }
        System.out.println("=== 探针结束 ===");
    }

    /**
     * 跑一个场景：先做 GC 取基线，再并发压 {@code rounds} 次，最后再 GC 取终值。
     *
     * @param name        场景名
     * @param gateway     被压的网关
     * @param rounds      总调用次数
     * @param concurrency 并发在途数
     * @param marker      写进消息里的 {@code bench-*} 标记
     */
    private static void scenario(String name, LlmGateway gateway, int rounds,
                                 int concurrency, String marker) throws Exception {
        scenario(name, gateway, rounds, concurrency, marker, Entry.PLAIN);
    }

    /**
     * 跑一个场景：先做 GC 取基线，再并发压 {@code rounds} 次，最后再 GC 取终值。
     *
     * @param name        场景名
     * @param gateway     被压的网关
     * @param rounds      总调用次数
     * @param concurrency 并发在途数
     * @param marker      写进消息里的 {@code bench-*} 标记
     * @param entry       走哪个网关入口
     */
    private static void scenario(String name, LlmGateway gateway, int rounds,
                                 int concurrency, String marker, Entry entry) throws Exception {
        System.out.println();
        System.out.println("--- " + name + " ---");
        Baseline before = snapshot(true);

        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final List<Throwable> firstErrors = Collections.synchronizedList(new ArrayList<Throwable>());

        long started = System.currentTimeMillis();
        // 峰值采样：GC 后的留存看不出「同一时刻占了多少」。
        // 大响应体的问题恰恰在峰值——一次 20MB 的流式响应会把整个响应体
        // 复制一份在堆里（transport 的 raw 累积器是空条件的）。
        final long[] peakHeap = new long[1];
        final boolean[] sampling = new boolean[]{true};
        Thread sampler = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sampling[0]) {
                    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    if (used > peakHeap[0]) {
                        peakHeap[0] = used;
                    }
                    sleep(20L);
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        int index = 0;
        while (index < rounds) {
            int batch = Math.min(concurrency, rounds - index);
            CountDownLatch latch = new CountDownLatch(batch);
            List<CompletableFuture<?>> futures = new ArrayList<CompletableFuture<?>>(batch);
            for (int i = 0; i < batch; i++) {
                CompletableFuture<?> future = fire(gateway, marker, entry, ok, failed, firstErrors);
                futures.add(future);
                future.whenComplete((value, error) -> latch.countDown());
            }
            // 每批最多等 60 秒；超时说明有 future 没收敛，这本身就是结论。
            boolean finished = latch.await(60L, TimeUnit.SECONDS);
            int settled = 0;
            for (CompletableFuture<?> future : futures) {
                if (future.isDone()) {
                    settled++;
                }
            }
            if (!finished) {
                System.out.println("  !! 批内 " + batch + " 个调用在 60 秒内只收敛了 " + settled + " 个");
            }
            index += batch;
        }
        long elapsed = System.currentTimeMillis() - started;
        sampling[0] = false;

        Baseline after = snapshot(true);
        System.out.printf("  调用 %d 次（成功 %d，失败 %d），耗时 %d ms%n",
                rounds, ok.get(), failed.get(), elapsed);
        report(before, after, rounds);
        System.out.printf("  堆峰值: %s（相对基线 +%s）%n",
                mb(peakHeap[0]), mb(peakHeap[0] - before.heapUsed));
        if (!firstErrors.isEmpty()) {
            Throwable first = firstErrors.get(0);
            System.out.println("  首个失败的异常类型: " + first.getClass().getName()
                    + " / " + brief(first.getMessage()));
        }
    }

    /** 场景决定走哪个网关入口和要不要重试。 */
    private enum Entry {
        /** 非流式单次调用。 */
        PLAIN,
        /** 流式单次调用（增量丢弃，只关心传输与解析的内存）。 */
        STREAM,
        /** 显式重试，用来压退避编排与在途重试集合。 */
        RETRY
    }

    /**
     * 发起一次调用，结果只计数，不在此处抛出。
     */
    private static CompletableFuture<?> fire(LlmGateway gateway, String marker, Entry entry,
                                             AtomicInteger ok, AtomicInteger failed,
                                             List<Throwable> firstErrors) {
        try {
            final CompletableFuture<?> future;
            if (entry == Entry.STREAM) {
                future = gateway.oneByStreamAsync(call(marker), null, null);
            } else if (entry == Entry.RETRY) {
                future = gateway.oneWithRetryAsync(call(marker), retryPolicy());
            } else {
                future = gateway.oneAsync(call(marker));
            }
            future.whenComplete((value, error) -> {
                if (error == null) {
                    ok.incrementAndGet();
                    return;
                }
                failed.incrementAndGet();
                Throwable cause = error instanceof java.util.concurrent.CompletionException
                        && error.getCause() != null ? error.getCause() : error;
                if (firstErrors.size() < 3) {
                    firstErrors.add(cause);
                }
            });
            return future;
        } catch (LlmCallException ex) {
            failed.incrementAndGet();
            if (firstErrors.size() < 3) {
                firstErrors.add(ex);
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            failed.incrementAndGet();
            if (firstErrors.size() < 3) {
                firstErrors.add(ex);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * @return 2 次尝试、退避很小的策略；只为把「重试编排」这条路径跑满，
     *         不关心退避时长本身（假服务返回 429 带 Retry-After）
     */
    private static RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoffMs(20L)
                .maxBackoffMs(100L)
                .build();
    }

    private static LlmCall call(String marker) {
        // 用 profile 而不是 biz：biz 要求业务绑定到 profile，探针只关心传输层，
        // 直接点名 profile 可以跳过业务绑定表（与手工构造网关的写法一致）。
        return LlmCall.profile("probe", "main",
                Collections.singletonList(LlmMessage.user(marker)))
                .timeouts(Integer.valueOf(5000), Integer.valueOf(120000));
    }

    /**
     * 单供应商单模型的测试配置，指向本机假服务。
     */
    private static ModelResolver resolver(String base) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("probe"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("fake-model");
        model.setReasoning(Boolean.TRUE);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));
        model.setContextWindow(Integer.valueOf(32768));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl(base);
        provider.setApiKey("fake-key");
        provider.setConnectTimeoutMs(Integer.valueOf(5000));
        provider.setReadTimeoutMs(Integer.valueOf(120000));
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("fake", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("fake");
        profile.setModel("fake-model");
        llm.getProfiles().put("main", profile);

        return new ModelResolver(llm);
    }

    /** 一次快照：堆已用字节、老年代、线程数。 */
    private static final class Baseline {
        long heapUsed;
        long heapMax;
        long oldGenUsed;
        int threads;
    }

    /**
     * @param forceGc true 表示先做两次 GC 再取数，得到「存活集」而不是「当前分配量」
     */
    private static Baseline snapshot(boolean forceGc) {
        if (forceGc) {
            for (int i = 0; i < 3; i++) {
                System.gc();
                sleep(200L);
            }
        }
        Baseline snap = new Baseline();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        snap.heapUsed = heap.getUsed();
        snap.heapMax = heap.getMax();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP
                    && pool.getName().toLowerCase(Locale.ROOT).contains("old")) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    snap.oldGenUsed += usage.getUsed();
                }
            }
        }
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            snap.threads = threads.getThreadCount();
        } catch (Throwable ignored) {
            snap.threads = -1;
        }
        return snap;
    }

    /**
     * 打印一个场景的增量。判据集中在两点：
     * 1) 堆增长是否随轮数线性走（线性 = 泄漏，平稳 = 正常）；
     * 2) 线程数是否只增不减（Jetty selector/连接线程每个客户端固定，不该随调用次数涨）。
     */
    private static void report(Baseline before, Baseline after, int rounds) {
        long heapDelta = after.heapUsed - before.heapUsed;
        long perCall = rounds == 0 ? 0L : heapDelta / rounds;
        System.out.printf("  堆: %s -> %s（增量 %s，每次调用 %s）%n",
                mb(before.heapUsed), mb(after.heapUsed), mb(heapDelta), bytes(perCall));
        if (before.oldGenUsed > 0 || after.oldGenUsed > 0) {
            System.out.printf("  老年代: %s -> %s（增量 %s）%n",
                    mb(before.oldGenUsed), mb(after.oldGenUsed),
                    mb(after.oldGenUsed - before.oldGenUsed));
        }
        System.out.printf("  线程: %d -> %d（增量 %+d）%n",
                before.threads, after.threads, after.threads - before.threads);
        // 线程名分布：Jetty 的 selector/连接/响应线程有固定前缀，
        // 如果增量集中在约束里的池名上，就是池扩容而不是泄漏。
        java.util.Map<String, Integer> names = threadNamesByPrefix();
        if (!names.isEmpty()) {
            System.out.println("  当前线程名前缀分布: " + names);
        }
    }

    /**
     * 按去掉末尾数字后的前缀统计线程数，只看前 6 个高频前缀。
     *
     * @return 前缀 -> 线程数
     */
    private static java.util.Map<String, Integer> threadNamesByPrefix() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            int end = name.length();
            while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
                end--;
            }
            String prefix = end == 0 ? name : name.substring(0, end);
            Integer current = counts.get(prefix);
            counts.put(prefix, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        java.util.List<java.util.Map.Entry<String, Integer>> entries =
                new ArrayList<java.util.Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
            @Override
            public int compare(java.util.Map.Entry<String, Integer> a,
                               java.util.Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        java.util.Map<String, Integer> top = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < entries.size() && i < 6; i++) {
            top.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return top;
    }

    private static long maxHeapMb() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024L * 1024L);
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String bytes(long value) {
        return String.format(Locale.ROOT, "%d B", value);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static long number(String[] args, String name, long fallback) {
        String prefix = name + "=";
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith(prefix)) {
                    try {
                        return Long.parseLong(arg.substring(prefix.length()).trim());
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    private static String option(String[] args, String name, String fallback) {
        String prefix = name + "=";
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith(prefix)) {
                    return arg.substring(prefix.length()).trim();
                }
            }
        }
        return fallback;
    }
}

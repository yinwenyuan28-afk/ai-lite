package io.github.yinwenyuan28.ailite.benchmark.fake;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor;
import io.github.yinwenyuan28.ailite.internal.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 取消句柄滞留探针：验证「调用方长期持有 {@code LlmCancelHandle} 时，
 * 整条 {@code AsyncRun} 是否跟着活下来」。
 *
 * <p>为什么单独做：子代理读代码发现 {@code AsyncRun} 全类没有
 * {@code cancel.bindAbort(null)}，而 {@code RetryRun} 有（{@code unbindOuter}）。
 * 绑定在调用方句柄上的那个 Runnable 是匿名内部类，捕获了 {@code AsyncRun}，
 * 而 {@code AsyncRun} 又持有 transcript、{@code ResolvedModel}（含 apiKey 明文）
 * 和最后一轮完整回复。</p>
 *
 * <p>要区分两件事：
 * <ul>
 *   <li>「句柄本身占多少」——句柄很轻，持有它本身不是问题；</li>
 *   <li>「句柄是否把整条调用链拖住」——这才是泄漏。</li>
 * </ul>
 * 所以探针做 A/B 对照：同样跑 N 次成功调用，一组把句柄存下来不清空，
 * 一组不存。两组都 GC 后比较存活对象，差值就是被句柄拖住的部分。</p>
 */
public final class CancelHandleRetentionProbeMain {

    private CancelHandleRetentionProbeMain() {
    }

    /**
     * @param args 支持 {@code --base=}、{@code --calls=}
     */
    public static void main(String[] args) throws Exception {
        String base = option(args, "--base", "http://127.0.0.1:18181/v1");
        int calls = (int) number(args, "--calls", 200L);

        System.out.println("=== 取消句柄滞留探针 ===");
        System.out.println("假服务: " + base);
        System.out.println("每轮调用数: " + calls);
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println();

        // 对照组：不保存句柄。调用结束即丢弃，AsyncRun 应能回收。
        long baseline = runAndMeasure(base, calls, false, "对照组：不保存句柄");
        // 实验组：保存句柄。如果 A1 成立，AsyncRun 全部滞留。
        long retained = runAndMeasure(base, calls, true, "实验组：保存句柄");

        System.out.println();
        System.out.printf("对照组堆占用: %s%n", mb(baseline));
        System.out.printf("实验组堆占用: %s%n", mb(retained));
        long delta = retained - baseline;
        System.out.printf("差值: %s（%d 次调用，平均每次 %d B）%n",
                mb(delta), calls, calls == 0 ? 0L : delta / calls);
        System.out.println();
        if (delta > 8L * 1024L * 1024L) {
            System.out.println("判定: 句柄确实拖住了大量对象 —— 需要给对应路径补解绑");
        } else if (delta > 1024L * 1024L) {
            System.out.println("判定: 有滞留但量级较小（可能只是句柄自身 + 少量闭包）");
        } else {
            System.out.println("判定: 没有明显滞留");
        }
        System.out.println();

        // 再跑一轮重试入口：RetryRun 走的是另一条绑定/解绑路径
        // （它有 unbindOuter，理论上应释放；这里验证两种入口的差异）。
        System.out.println("=== 重试入口（oneWithRetryAsync）===");
        long retryBaseline = runRetryAndMeasure(base, calls, false);
        long retryRetained = runRetryAndMeasure(base, calls, true);
        long retryDelta = retryRetained - retryBaseline;
        System.out.printf("对照组 %s / 实验组 %s / 差值 %s%n",
                mb(retryBaseline), mb(retryRetained), mb(retryDelta));
        System.out.println(retryDelta > 8L * 1024L * 1024L
                ? "判定: 重试入口也滞留" : "判定: 重试入口释放正常（符合 unbindOuter 的预期）");
        System.out.println("=== 探针结束 ===");
    }

    /**
     * 重试入口的 A/B 对照。
     *
     * @param base  假服务地址
     * @param calls 调用次数
     * @param keep  是否保存句柄
     * @return GC 后堆占用
     */
    private static long runRetryAndMeasure(String base, int calls, boolean keep) throws Exception {
        AiLiteConfig.Http http = new AiLiteConfig.Http();
        http.setTransport("jetty");
        JettyHttpExecutor executor = new JettyHttpExecutor(http);
        AiLiteConfig.Executor execCfg = new AiLiteConfig.Executor();
        execCfg.setCoreSize(4);
        execCfg.setMaxSize(8);
        execCfg.setQueueCapacity(1024);
        execCfg.setThreadNamePrefix("ret-probe2-");
        PlatformExecutorProvider provider = new PlatformExecutorProvider(execCfg);
        LlmGateway gateway = new LlmGateway(resolver(base),
                Collections.singletonList(new OpenAiCompletionsTransport(executor)),
                new NoopLlmCallStore(), provider, new SequentialToolExecutor());

        List<LlmCancelHandle> kept = new ArrayList<LlmCancelHandle>();
        try {
            for (int i = 0; i < calls; i++) {
                LlmCancelHandle handle = new LlmCancelHandle();
                if (keep) {
                    kept.add(handle);
                }
                try {
                    gateway.oneWithRetryAsync(call(), io.github.yinwenyuan28.ailite.llm
                            .RetryPolicy.defaults(), handle).get(30L, TimeUnit.SECONDS);
                } catch (LlmCallException ex) {
                    System.out.println("  第 " + i + " 次重试调用失败: " + ex.getMessage());
                } catch (java.util.concurrent.TimeoutException ex) {
                    System.out.println("  第 " + i + " 次重试调用超时");
                }
            }
            gateway.shutdown();
            executor.close();
            provider.close();
            Thread.sleep(2000L);
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(300L);
            }
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            System.out.printf("  重试入口 GC 后堆: %s（保存 %d 个句柄）%n", mb(used), kept.size());
            return used;
        } finally {
            kept.clear();
        }
    }

    /**
     * 跑 N 次成功调用，GC 后返回堆占用。
     *
     * @param base    假服务地址
     * @param calls   调用次数
     * @param keep    是否把句柄保存下来
     * @param label   打印用的组名
     * @return GC 后的堆已用字节
     */
    private static long runAndMeasure(String base, int calls, boolean keep, String label)
            throws Exception {
        System.out.println("--- " + label + " ---");

        AiLiteConfig.Http http = new AiLiteConfig.Http();
        http.setTransport("jetty");
        JettyHttpExecutor executor = new JettyHttpExecutor(http);
        AiLiteConfig.Executor execCfg = new AiLiteConfig.Executor();
        execCfg.setCoreSize(4);
        execCfg.setMaxSize(8);
        execCfg.setQueueCapacity(1024);
        execCfg.setThreadNamePrefix("ret-probe-");
        PlatformExecutorProvider provider = new PlatformExecutorProvider(execCfg);
        LlmGateway gateway = new LlmGateway(resolver(base),
                Collections.singletonList(new OpenAiCompletionsTransport(executor)),
                new NoopLlmCallStore(), provider, new SequentialToolExecutor());

        List<LlmCancelHandle> kept = new ArrayList<LlmCancelHandle>();
        try {
            for (int i = 0; i < calls; i++) {
                LlmCancelHandle handle = new LlmCancelHandle();
                if (keep) {
                    kept.add(handle);
                }
                try {
                    final CompletableFuture<?> future = gateway.oneAsync(call(), handle);
                    future.get(30L, TimeUnit.SECONDS);
                } catch (LlmCallException ex) {
                    System.out.println("  第 " + i + " 次调用失败: " + ex.getMessage());
                } catch (java.util.concurrent.TimeoutException ex) {
                    System.out.println("  第 " + i + " 次调用超时");
                }
            }
            // 关掉传输与网关，确保除了句柄以外的东西都能回收。
            gateway.shutdown();
            executor.close();
            provider.close();

            // 等 Jetty 线程退出、连接池释放，再做 GC。
            Thread.sleep(2000L);
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(300L);
            }
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            System.out.printf("  GC 后堆占用: %s（保存了 %d 个句柄）%n", mb(used), kept.size());
            return used;
        } finally {
            // kept 里有引用，必须显式清空，否则下一组测量会被它污染。
            kept.clear();
        }
    }

    private static LlmCall call() {
        return LlmCall.profile("probe", "main",
                Collections.singletonList(LlmMessage.user("hi")))
                .timeouts(Integer.valueOf(5000), Integer.valueOf(60000));
    }

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
        provider.setApiKey("fake-key-should-not-survive");
        provider.setConnectTimeoutMs(Integer.valueOf(5000));
        provider.setReadTimeoutMs(Integer.valueOf(60000));
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("fake", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("fake");
        profile.setModel("fake-model");
        llm.getProfiles().put("main", profile);
        return new ModelResolver(llm);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
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

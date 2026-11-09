package io.github.yinwenyuan28.ailite.benchmark.fake;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor;
import io.github.yinwenyuan28.ailite.internal.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 堆上限探针：一次只发一个 N MB 的流式响应，看「一个这样的响应需要多少堆」。
 *
 * <p>为什么单独做：{@code MemoryProbeMain} 测出的「19 倍放大」是在 3.4GB 大堆下
 * 得到的——堆越大 GC 越懒，峰值读数里混着「本可以回收但还没回收」的垃圾。
 * 那个数字不能直接当「最小需要多少内存」用。</p>
 *
 * <p>这里换成另一种问法：把堆压到很小，只发一个响应，成功还是失败。
 * 这样得到的是**硬下界**——低于这个堆一个响应就装不下，与 GC 时机无关。</p>
 */
public final class HeapCeilingProbeMain {

    private HeapCeilingProbeMain() {
    }

    /**
     * @param args 支持 {@code --base=}、{@code --mb=}
     */
    public static void main(String[] args) throws Exception {
        String base = option(args, "--base", "http://127.0.0.1:18181/v1");
        int mb = (int) number(args, "--mb", 4L);

        long maxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        System.out.println("=== 堆上限探针 ===");
        System.out.println("JVM: " + System.getProperty("java.version")
                + "，最大堆: " + toMb(maxHeap));
        System.out.printf("单个响应约 %d MB，模式: %s%n", mb,
                "false".equals(option(args, "--stream", "true")) ? "非流式" : "流式");

        // 用 64 条事件分摊，单条体积 = 总量 / 64。
        // chunk-size 单位是字符，'x' 在 JSON 里占 1 字节，所以字符数≈字节数。
        int chunks = 64;
        int sizePerChunk = (int) ((long) mb * 1024L * 1024L / chunks);
        String marker = "bench-chunks=" + chunks + " bench-chunk-size=" + sizePerChunk;

        AiLiteConfig.Http http = new AiLiteConfig.Http();
        http.setTransport("jetty");
        JettyHttpExecutor executor = new JettyHttpExecutor(http);
        AiLiteConfig.Executor execCfg = new AiLiteConfig.Executor();
        execCfg.setCoreSize(2);
        execCfg.setMaxSize(4);
        execCfg.setQueueCapacity(64);
        execCfg.setThreadNamePrefix("heap-probe-");
        PlatformExecutorProvider provider = new PlatformExecutorProvider(execCfg);
        LlmGateway gateway = new LlmGateway(resolver(base),
                Collections.singletonList(new OpenAiCompletionsTransport(executor)),
                new NoopLlmCallStore(), provider, new SequentialToolExecutor());

        String outcome;
        int contentChars = -1;
        boolean useStream = !"false".equals(option(args, "--stream", "true"));
        try {
            LlmCall call = LlmCall.profile("probe", "main",
                            Collections.singletonList(LlmMessage.user(marker)))
                    .timeouts(Integer.valueOf(5000), Integer.valueOf(180000));
            CompletableFuture<?> future = useStream
                    ? gateway.oneByStreamAsync(call, null, null)
                    : gateway.oneAsync(call);
            Object result = future.get(180L, TimeUnit.SECONDS);
            outcome = "成功";
            if (result instanceof io.github.yinwenyuan28.ailite.llm.LlmResult) {
                String text = ((io.github.yinwenyuan28.ailite.llm.LlmResult) result).getContent();
                contentChars = text == null ? 0 : text.length();
            }
        } catch (Throwable ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            outcome = cause.getClass().getSimpleName() + ": " + brief(cause.getMessage());
        } finally {
            gateway.shutdown();
            executor.close();
            provider.close();
        }

        long usedAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.println("结果: " + outcome);
        System.out.println("返回内容长度: " + (contentChars < 0 ? "无（失败）" : contentChars + " 字符"));
        System.out.println("结束后堆: " + toMb(usedAfter));
        System.out.println("=== 结束 ===");
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
        provider.setApiKey("fake-key");
        provider.setConnectTimeoutMs(Integer.valueOf(5000));
        provider.setReadTimeoutMs(Integer.valueOf(180000));
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("fake", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("fake");
        profile.setModel("fake-model");
        llm.getProfiles().put("main", profile);
        return new ModelResolver(llm);
    }

    private static String toMb(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 100 ? text.substring(0, 100) : text;
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

package io.github.yinwenyuan28.ailite.benchmark.fake;

/**
 * 栈深度探针：直接量 Jackson 解析深层嵌套 JSON 的上限。
 *
 * <p>为什么单独做：网关场景里看到的「深嵌套不炸」是假象——我之前把嵌套
 * 塞在 content 字符串里了，那只是普通文本。要判断「恶意或损坏的响应能不能
 * 把解析线程的栈打爆」，必须让嵌套出现在真正的 JSON 结构位置上。</p>
 *
 * <p>它回答两个问题：
 * <ol>
 *   <li>Jackson 的默认嵌套上限是多少（版本相关）；</li>
 *   <li>在 JDK 8 默认栈大小下，解析到上限之前会不会先 StackOverflowError。</li>
 * </ol>
 * 结论直接决定：是否需要给 transport 的解析加深度限制。</p>
 */
public final class StackDepthProbeMain {

    private StackDepthProbeMain() {
    }

    /**
     * @param args 无
     */
    public static void main(String[] args) {
        System.out.println("=== 栈深度探针 ===");
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Jackson: " + jacksonVersion());
        System.out.println();

        // 从小到大试，找到第一个不是「解析成功」的深度。
        int[] candidates = {100, 500, 999, 1000, 1001, 2000, 5000, 20000, 100000};
        int firstBlocked = -1;
        for (int depth : candidates) {
            String json = nested(depth);
            long started = System.nanoTime();
            String outcome;
            try {
                Object parsed = JsonBridge.parse(json);
                outcome = parsed == null ? "parsed-null" : "parsed";
            } catch (StackOverflowError error) {
                outcome = "StackOverflowError";
            } catch (Throwable error) {
                outcome = error.getClass().getSimpleName() + ": " + brief(error.getMessage());
            }
            long micros = (System.nanoTime() - started) / 1000L;
            System.out.printf("  深度 %6d -> %-60s (%d us)%n", depth, outcome, micros);
            if (firstBlocked < 0 && !"parsed".equals(outcome)) {
                firstBlocked = depth;
            }
        }
        System.out.println();
        if (firstBlocked > 0) {
            System.out.println("第一个被拒绝/失败的深度: " + firstBlocked);
        } else {
            System.out.println("所有测试深度都没有失败 —— 说明解析器没有有效的深度上限，"
                    + "需要自己加限制");
        }
        System.out.println("=== 探针结束 ===");
    }

    /**
     * 造 {@code depth} 层嵌套数组，形如 [[[...0...]]]。
     */
    private static String nested(int depth) {
        StringBuilder text = new StringBuilder(depth * 2 + 8);
        for (int i = 0; i < depth; i++) {
            text.append('[');
        }
        text.append('0');
        for (int i = 0; i < depth; i++) {
            text.append(']');
        }
        return text.toString();
    }

    /**
     * 用 transport 实际使用的那个 Jackson 入口解析，保证结论与生产一致。
     */
    private static final class JsonBridge {
        static Object parse(String json) throws Exception {
            // readTree 就是 transport 解析响应用的入口（TransportSupport.parse 最终也走它）。
            return io.github.yinwenyuan28.ailite.json.AiLiteJson.readTree(json);
        }
    }

    private static String jacksonVersion() {
        try {
            Package pkg = com.fasterxml.jackson.databind.ObjectMapper.class.getPackage();
            return pkg == null ? "unknown" : String.valueOf(pkg.getImplementationVersion());
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}

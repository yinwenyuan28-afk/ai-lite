package io.github.yinwenyuan28.ailite.benchmark.fake;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次假响应的行为参数。
 *
 * <p>设计意图：压测脚本不改服务端就能切换「病症」。所有参数都从请求体里的
 * {@code bench-*} 标记解析，因为调用方（LlmBenchService、WorktypeClean）本来
 * 就会把业务描述塞进 user 消息，正好一起带过来。</p>
 *
 * <p>参数不合法时一律回落到默认值，不抛异常——假服务本身不应该成为压测里的
 * 失败来源，否则分不清是库的问题还是假服务的问题。</p>
 */
final class FakeBehavior {

    /** 首字节前挂住多少秒。0 表示立刻响应。 */
    final long holdSeconds;
    /** SSE 事件条数。 */
    final int chunks;
    /** 每个事件 content 字段重复多少个字符，用来放大单条消息体积。 */
    final int chunkSize;
    /** 是否返回流式。非流式时一次性返回完整 JSON。 */
    final boolean stream;
    /** 返回的 HTTP 状态码；非 2xx 时直接走错误分支。 */
    final int status;
    /** Retry-After 头的值；null 表示不返回该头。 */
    final String retryAfter;
    /** JSON 嵌套深度，用来试探解析器递归。0 表示正常响应。 */
    final int deepDepth;
    /** 发出多少个 tool_calls，用来试探工具循环。 */
    final int toolCalls;
    /** true 表示发完响应头就断开，用来复现「响应提前结束」。 */
    final boolean disconnectMidStream;
    /** true 表示只建立连接、永远不发响应头，用来复现读超时。 */
    final boolean hangBeforeHeaders;
    /** 是否省略 usage 字段。 */
    final boolean omitUsage;
    /** true 表示只发 data 行、永不发空行：用来压 SseCollector 的单事件累积上限。 */
    final boolean neverEndEvent;
    /** 每行之间等待多少毫秒，用来控制累积速率。 */
    final long lineDelayMs;

    private FakeBehavior(Map<String, String> raw, String body) {
        this.holdSeconds = num(raw, "bench-hold", 0L);
        this.chunks = (int) num(raw, "bench-chunks", 8L);
        this.chunkSize = (int) num(raw, "bench-chunk-size", 1L);
        this.stream = "true".equals(raw.get("bench-stream"))
                // 真实调用方（LlmGateway 的流式入口）会在请求体里写 "stream":true，
                // 这里也要认，否则假服务对着真实调用永远走非流式分支。
                || isStreamFlagSet(body);
        this.status = (int) num(raw, "bench-status", 200L);
        this.retryAfter = raw.get("bench-retry-after");
        this.deepDepth = (int) num(raw, "bench-deep", 0L);
        this.toolCalls = (int) num(raw, "bench-tool-calls", 0L);
        this.disconnectMidStream = "true".equals(raw.get("bench-disconnect"));
        this.hangBeforeHeaders = "true".equals(raw.get("bench-hang-headers"));
        this.omitUsage = "true".equals(raw.get("bench-no-usage"));
        this.neverEndEvent = "true".equals(raw.get("bench-never-end"));
        this.lineDelayMs = num(raw, "bench-line-delay", 0L);
    }

    /**
     * 从请求体里抽出所有 {@code bench-*} 标记。
     *
     * <p>不解析 JSON：假的请求体也不一定合法，而这里只需要键值对。
     * 匹配形如 {@code bench-hold=30} 的片段，遇到非字母数字、非点号的字符即结束。</p>
     *
     * @param body 请求体，可为 null
     * @return 标记键值对，保持出现顺序
     */
    static FakeBehavior parse(String body) {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        if (body != null) {
            int from = 0;
            while (true) {
                int at = body.indexOf("bench-", from);
                if (at < 0) {
                    break;
                }
                int cursor = at;
                while (cursor < body.length() && isKeyChar(body.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= body.length() || body.charAt(cursor) != '=') {
                    from = at + 6;
                    continue;
                }
                String key = body.substring(at, cursor);
                int valueStart = cursor + 1;
                int valueEnd = valueStart;
                while (valueEnd < body.length() && isValueChar(body.charAt(valueEnd))) {
                    valueEnd++;
                }
                raw.put(key, body.substring(valueStart, valueEnd));
                from = valueEnd;
            }
        }
        return new FakeBehavior(raw, body);
    }

    /**
     * 判断请求体里有没有真实的 {@code "stream":true} 开关。
     *
     * <p>只认 JSON 里带引号的键，避免把 content 正文里出现的 stream 字样当成开关。
     * 空白差异（{@code "stream": true}）也一并容忍。</p>
     *
     * @param body 请求体，可为 null
     * @return true 表示调用方要求流式
     */
    private static boolean isStreamFlagSet(String body) {
        if (body == null) {
            return false;
        }
        int at = body.indexOf("\"stream\"");
        if (at < 0) {
            return false;
        }
        int cursor = at + 8;
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= body.length() || body.charAt(cursor) != ':') {
            return false;
        }
        cursor++;
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
            cursor++;
        }
        return body.startsWith("true", cursor);
    }

    /**
     * 键名允许字母、数字、连字符；遇到等号或空白即结束。
     */
    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-';
    }

    /**
     * 值允许字母、数字、点号、连字符、冒号（Retry-After 可能是日期）；空白即结束。
     */
    private static boolean isValueChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':';
    }

    /**
     * @return 解析后的数值；缺失或非法时返回默认值
     */
    private static long num(Map<String, String> raw, String key, long fallback) {
        String value = raw.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0L ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

package io.github.yinwenyuan28.ailite.benchmark.fake;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 假模型服务的请求处理。
 *
 * <p>支持三条路径，对应 ai-lite 的三种协议：
 * <ul>
 *   <li>{@code POST .../chat/completions} —— OpenAI Chat Completions</li>
 *   <li>{@code POST .../responses} —— OpenAI Responses</li>
 *   <li>{@code POST .../v1/messages} —— Anthropic Messages</li>
 *   <li>{@code GET .../models} —— 模型同步探测</li>
 * </ul>
 * 路径按后缀匹配，不管 base-url 前面挂了多少段。</p>
 *
 * <p>行为由请求体里的 {@code bench-*} 标记控制，见 {@link FakeBehavior}。
 * 每个请求都独立决定行为，因此同一轮压测里可以混跑正常流、深嵌套、断开、
 * 429 重试等各种情形。</p>
 */
final class FakeHandler extends AbstractHandler {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 统计用：处理过的请求数，压测结束时打印，用来核对有没有丢请求。 */
    private final Counter counter;

    FakeHandler(Counter counter) {
        this.counter = counter;
    }

    @Override
    public void handle(String target, Request baseRequest,
                       HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        counter.total.incrementAndGet();
        String path = target == null ? "" : target;
        try {
            if (path.endsWith("/models")) {
                models(response);
                baseRequest.setHandled(true);
                return;
            }
            String body = readAll(request.getInputStream());
            FakeBehavior plan = FakeBehavior.parse(body);
            counter.record(plan);

            // 只建立连接、不发响应头：复现读超时。等待超过 read-timeout 后
            // 客户端会自己放弃；这里挂到 10 分钟，确保不会先于客户端结束。
            if (plan.hangBeforeHeaders) {
                sleep(600_000L);
                baseRequest.setHandled(true);
                return;
            }
            // 先发头再断开：客户端会收到 200 然后读到 EOF。
            if (plan.disconnectMidStream) {
                response.setStatus(200);
                response.setContentType("text/event-stream");
                response.flushBuffer();
                // 返回即由 Jetty 关闭该连接（Connection: close）。
                response.setHeader("Connection", "close");
                baseRequest.setHandled(true);
                return;
            }
            if (!isSuccessful(plan.status)) {
                error(response, plan);
                baseRequest.setHandled(true);
                return;
            }
            if (path.endsWith("/responses")) {
                responses(response, plan);
            } else if (path.endsWith("/v1/messages")) {
                anthropic(response, plan);
            } else {
                completions(response, plan);
            }            baseRequest.setHandled(true);
        } catch (IOException ex) {
            if (!response.isCommitted()) {
                response.sendError(500);
            }
        } finally {
            counter.finished.incrementAndGet();
        }
    }

    /** GET {base}/models：管理页同步模型用。 */
    private void models(HttpServletResponse response) throws IOException {
        byte[] body = ("{\"object\":\"list\",\"data\":["
                + "{\"id\":\"fake-model\",\"object\":\"model\"}]}").getBytes(UTF_8);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** 非 2xx：带状态码，可选带 Retry-After，用来压重试与退避。 */
    private void error(HttpServletResponse response, FakeBehavior plan) throws IOException {
        byte[] body = ("{\"error\":{\"message\":\"fake upstream error\",\"type\":\"fake\"}}")
                .getBytes(UTF_8);
        response.setStatus(plan.status);
        if (plan.retryAfter != null) {
            response.setHeader("Retry-After", plan.retryAfter);
        }
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** OpenAI Chat Completions。 */
    private void completions(HttpServletResponse response, FakeBehavior plan) throws IOException {
        sleep(seconds(plan.holdSeconds));
        String content = repeat("x", plan.chunkSize);
        if (!plan.stream) {
            StringBuilder json = new StringBuilder();
            json.append("{\"choices\":[{\"message\":{\"content\":\"")
                    .append(escape(content))
                    .append("\"},\"finish_reason\":\"stop\"}]");
            if (!plan.omitUsage) {
                json.append(",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                        + "\"total_tokens\":2}");
            }
            // 深嵌套必须放在真正的 JSON 结构位置上，解析器才会递归下去。
            // 塞进 content 字符串里是无效的：那只是一段普通文本。
            if (plan.deepDepth > 0) {
                json.append(",\"deep\":").append(deepPayload(plan.deepDepth));
            }
            json.append('}');
            writeJson(response, json.toString());
            return;
        }
        response.setStatus(200);
        response.setContentType("text/event-stream");
        ServletOutputStream out = response.getOutputStream();
        // 「永不结束事件」模式：只发 data: 行，不发空行。
        // SseCollector 只在空行时清空累积器，所以这会让它的 StringBuilder 一直长。
        if (plan.neverEndEvent) {
            for (int i = 0; i < plan.chunks; i++) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\""
                        + escape(repeat("x", Math.max(1, plan.chunkSize))) + "\"}}]}\n")
                        .getBytes(UTF_8));
                out.flush();
                if (plan.lineDelayMs > 0L) {
                    sleep(plan.lineDelayMs);
                }
            }
            // 不发空行、不发 [DONE]，直接结束连接。
            return;
        }
        if (plan.toolCalls > 0) {
            for (int i = 0; i < plan.toolCalls; i++) {
                out.write(("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":" + i
                        + ",\"id\":\"call_" + i + "\",\"function\":{\"name\":\"noop\","
                        + "\"arguments\":\"{}\"}}]}}]}\n\n").getBytes(UTF_8));
                out.flush();
            }
            out.write(("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                    .getBytes(UTF_8));
            out.flush();
            out.write("data: [DONE]\n\n".getBytes(UTF_8));
            out.flush();
            return;
        }
        for (int i = 0; i < plan.chunks; i++) {
            StringBuilder delta = new StringBuilder();
            delta.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                    .append(escape(content))
                    .append("\"}}");
            // 流式下的深嵌套放在事件 JSON 的结构位置（顶层 deep 字段），
            // 而不是 content 字符串里，才能压到解析器的递归。
            if (plan.deepDepth > 0) {
                delta.append(",\"deep\":").append(deepPayload(plan.deepDepth));
            }
            delta.append("]}\n\n");
            out.write(delta.toString().getBytes(UTF_8));
            out.flush();
        }
        StringBuilder tail = new StringBuilder();
        tail.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]");
        if (!plan.omitUsage) {
            tail.append(",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                    + "\"total_tokens\":2}");
        }
        tail.append("}\n\n");
        out.write(tail.toString().getBytes(UTF_8));
        out.flush();
        out.write("data: [DONE]\n\n".getBytes(UTF_8));
        out.flush();
    }

    /** OpenAI Responses。 */
    private void responses(HttpServletResponse response, FakeBehavior plan) throws IOException {
        sleep(seconds(plan.holdSeconds));
        String content = repeat("x", plan.chunkSize);
        if (!plan.stream) {
            writeJson(response, "{\"output\":[{\"type\":\"message\",\"content\":["
                    + "{\"type\":\"output_text\",\"text\":\"" + escape(content) + "\"}]}],"
                    + "\"status\":\"completed\","
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}");
            return;
        }
        response.setStatus(200);
        response.setContentType("text/event-stream");
        ServletOutputStream out = response.getOutputStream();
        for (int i = 0; i < plan.chunks; i++) {
            out.write(("data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                    + "\"content_index\":0,\"delta\":\"" + escape(content) + "\"}\n\n")
                    .getBytes(UTF_8));
            out.flush();
        }
        out.write(("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n")
                .getBytes(UTF_8));
        out.flush();
    }

    /** Anthropic Messages。 */
    private void anthropic(HttpServletResponse response, FakeBehavior plan) throws IOException {
        sleep(seconds(plan.holdSeconds));
        String content = repeat("x", plan.chunkSize);
        if (!plan.stream) {
            writeJson(response, "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                    + "{\"type\":\"text\",\"text\":\"" + escape(content) + "\"}],"
                    + "\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
            return;
        }
        response.setStatus(200);
        response.setContentType("text/event-stream");
        ServletOutputStream out = response.getOutputStream();
        for (int i = 0; i < plan.chunks; i++) {
            out.write(("data: {\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + escape(content) + "\"}}\n\n")
                    .getBytes(UTF_8));
            out.flush();
        }
        out.write(("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":1}}\n\n").getBytes(UTF_8));
        out.flush();
    }

    /**
     * 造一个深度为 {@code depth} 的嵌套 JSON 数组。
     *
     * <p>用来试探解析器的递归深度。Jackson 默认限制嵌套深度（2.10+ 为 1000），
     * 这里故意造得比上限深，看客户端是干净地报解析错误还是栈溢出。</p>
     *
     * @param depth 嵌套层数
     * @return 形如 [[[[...]]]] 的文本
     */
    private static String deepPayload(int depth) {
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

    private static void writeJson(HttpServletResponse response, String json) throws IOException {
        byte[] body = json.getBytes(UTF_8);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 重复字符，但不构造超长串时不做中间拷贝。 */
    private static String repeat(String unit, int times) {
        if (times <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static long seconds(long value) {
        return value <= 0L ? 0L : value * 1000L;
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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

    /** 请求计数，用来在结束时报出「收了多少、走完多少」。 */
    static final class Counter {
        final java.util.concurrent.atomic.AtomicLong total =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong finished =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong streamed =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong errored =
                new java.util.concurrent.atomic.AtomicLong();

        void record(FakeBehavior plan) {
            if (plan.stream) {
                streamed.incrementAndGet();
            }
            if (!isSuccessful(plan.status)) {
                errored.incrementAndGet();
            }
        }
    }
}

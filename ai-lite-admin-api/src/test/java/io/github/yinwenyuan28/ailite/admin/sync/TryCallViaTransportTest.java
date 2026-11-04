package io.github.yinwenyuan28.ailite.admin.sync;

import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.transport.AnthropicMessagesTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiResponsesTransport;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「测试调用」走真实协议适配器时的行为。
 *
 * <p>回归重点：手写实现只支持 OpenAI 兼容协议，Anthropic / Responses 直接返回
 * 「尚未接入」。改走传输层之后三种协议都能测，且请求地址与业务调用一致。</p>
 */
public class TryCallViaTransportTest {

    /** 记录最后一次出站请求的地址、头与体。 */
    private static final class CapturingHttp implements HttpExecutor {
        String url;
        Map<String, String> headers;
        String body;

        @Override
        public HttpResult execute(HttpRequest request) {
            capture(request);
            return new HttpResult(200, successBody());
        }

        /**
         * Anthropic 与 Responses 传输内部走流式接口，这里回放一段合法的 SSE。
         */
        @Override
        public void executeStreaming(HttpRequest request, StreamListener listener) {
            capture(request);
            try {
                for (String line : sseLines()) {
                    listener.onLine(line);
                }
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        private void capture(HttpRequest request) {
            url = request.getUrl();
            headers = request.getHeaders();
            body = request.getBody();
        }

        @Override
        public void close() {
        }
    }

    /** OpenAI 补全的成功响应体。 */
    private static String successBody() {
        return "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                + "\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    /**
     * 覆盖三种协议的最小 SSE 片段。内容不求完整，只求不因解析失败而中断，
     * 因为本用例只关心「请求发出去了、地址与头对不对」。
     *
     * @return SSE 行
     */
    private static java.util.List<String> sseLines() {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        // OpenAI Responses 的完成事件。
        lines.add("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\","
                + "\"model\":\"m\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}");
        // Anthropic 的结束事件。
        lines.add("data: {\"type\":\"message_stop\"}");
        lines.add("data: [DONE]");
        return lines;
    }

    private static ResolvedModel model(String api, String baseUrl) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Provider-Source", "from-config");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("vendor_field", "v");
        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.put("temperature", Double.valueOf(0.25));
        return new ResolvedModel("demo", api, baseUrl, "secret-key", headers,
                "qwen", 32768, 4096, false, false, LlmThinkingLevel.OFF,
                Collections.<String, String>emptyMap(), "none",
                sampler, extra, 10000, 30000);
    }

    /**
     * OpenAI 兼容协议：地址、自定义头、采样参数与附加字段都要进请求。
     */
    @Test
    public void openAiCompletionsCarriesHeadersAndBody() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.TryResult result = ModelSyncClient.tryChatViaTransport(
                new OpenAiCompletionsTransport(http),
                model("openai-completions", "http://example/v1"), Integer.valueOf(256), 5000);

        assertThat(result.isOk()).isTrue();
        assertThat(http.url).isEqualTo("http://example/v1/chat/completions");
        assertThat(http.headers).containsEntry("X-Provider-Source", "from-config");
        assertThat(http.headers.get("Authorization")).isEqualTo("Bearer secret-key");
        assertThat(http.body).contains("\"temperature\":0.25");
        assertThat(http.body).contains("vendor_field");
        // 回显里能看到真实自定义头，且密钥脱敏。
        assertThat(result.getRequestText()).contains("X-Provider-Source: from-config");
        assertThat(result.getRequestText()).doesNotContain("secret-key");
    }

    /**
     * 自定义消息要真的发出去，不是只显示在回显里。
     */
    @Test
    public void customPromptReachesRequest() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.tryChatViaTransport(new OpenAiCompletionsTransport(http),
                model("openai-completions", "http://example/v1"),
                "把这句话翻译成英文：你好世界", Integer.valueOf(256), 5000);

        assertThat(http.body).contains("把这句话翻译成英文：你好世界");
        assertThat(http.body).doesNotContain("你好\"");
    }

    /**
     * 消息里带引号、换行、反斜杠时，请求体仍必须是合法 JSON。
     */
    @Test
    public void customPromptIsEscaped() throws Exception {
        CapturingHttp http = new CapturingHttp();
        String tricky = "他说\"你好\"\n然后换行\\反斜杠";
        ModelSyncClient.tryChatViaTransport(new OpenAiCompletionsTransport(http),
                model("openai-completions", "http://example/v1"),
                tricky, Integer.valueOf(256), 5000);

        // 能被 Jackson 解析回来，说明引号与换行都正确转义了。
        com.fasterxml.jackson.databind.JsonNode parsed =
                io.github.yinwenyuan28.ailite.json.AiLiteJson.readTree(http.body);
        assertThat(parsed.get("messages").get(0).get("content").asText()).isEqualTo(tricky);
    }

    /**
     * 空消息回落成默认问候，不会发出空内容。
     */
    @Test
    public void blankPromptFallsBackToDefault() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.tryChatViaTransport(new OpenAiCompletionsTransport(http),
                model("openai-completions", "http://example/v1"),
                "   ", Integer.valueOf(256), 5000);

        assertThat(http.body).contains(ModelSyncClient.DEFAULT_PROBE_PROMPT);
    }

    /**
     * 回显里的请求体也用自定义消息，且转义正确。
     */
    @Test
    public void requestEchoUsesCustomPrompt() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.TryResult result = ModelSyncClient.tryChatViaTransport(
                new OpenAiCompletionsTransport(http),
                model("openai-completions", "http://example/v1"),
                "带\"引号\"的消息", Integer.valueOf(256), 5000);

        assertThat(result.getRequestText()).contains("带");
        // 回显是 JSON 序列化生成的，引号会变成 \" 而不是破坏结构。
        assertThat(result.getRequestText()).contains("\\\"引号\\\"");
    }

    /**
     * Anthropic 协议此前直接「尚未接入」，现在能测，且地址是 /v1/messages。
     */
    @Test
    public void anthropicIsSupportedNow() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.TryResult result = ModelSyncClient.tryChatViaTransport(
                new AnthropicMessagesTransport(http),
                model("anthropic-messages", "http://example"), Integer.valueOf(256), 5000);

        assertThat(http.url).isEqualTo("http://example/v1/messages");
        assertThat(result.getMessage()).doesNotContain("尚未接入");
    }

    /**
     * Responses 协议同样支持，地址是 /responses。
     */
    @Test
    public void responsesIsSupportedNow() {
        CapturingHttp http = new CapturingHttp();
        ModelSyncClient.TryResult result = ModelSyncClient.tryChatViaTransport(
                new OpenAiResponsesTransport(http),
                model("openai-responses", "http://example/v1"), Integer.valueOf(256), 5000);

        assertThat(http.url).isEqualTo("http://example/v1/responses");
        assertThat(result.getMessage()).doesNotContain("尚未接入");
    }

    /**
     * 没有适配器时明确失败，不抛异常。
     */
    @Test
    public void missingTransportFailsCleanly() {
        ModelSyncClient.TryResult result = ModelSyncClient.tryChatViaTransport(
                null, model("openai-completions", "http://example/v1"), null, 5000);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("协议适配器");
    }
}

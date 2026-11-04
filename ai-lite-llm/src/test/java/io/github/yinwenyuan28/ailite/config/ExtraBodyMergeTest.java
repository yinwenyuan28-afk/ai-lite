package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附加请求体的三层合并：供应商级、模型级、单次调用。
 *
 * <p>回归重点：
 * <ul>
 *   <li>两级之间此前是 {@code putAll}（整块替换），模型级写一个同父键的小项会把
 *       供应商级该父键下的子键全冲掉。现在改为递归合并。</li>
 *   <li>{@code LlmCall} 此前没有 {@code extraBody}，单次调用临时加字段做不到；
 *       网关也一直传 null。现已打通。</li>
 * </ul>
 */
public class ExtraBodyMergeTest {

    /** 抓取最后一次出站请求体。 */
    private static final class CapturingHttp implements HttpExecutor {
        String body;

        @Override
        public HttpResult execute(HttpRequest request) {
            body = request.getBody();
            String json = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            return new HttpResult(200, json);
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /**
     * 模型级只补一个子键，供应商级的同父子键必须保留。
     */
    @Test
    public void providerAndModelNestedKeysAreMergedNotReplaced() {
        AiLiteConfig.Llm llm = llm();

        // 供应商级
        Map<String, Object> providerKv = new LinkedHashMap<String, Object>();
        providerKv.put("enable_thinking", Boolean.TRUE);
        providerKv.put("max_new_tokens", Integer.valueOf(512));
        Map<String, Object> providerExtra = new LinkedHashMap<String, Object>();
        providerExtra.put("chat_template_kwargs", providerKv);
        llm.getProviders().get("demo").setExtraBody(providerExtra);

        // 模型级：同一个父键，只加一个子键
        Map<String, Object> modelKv = new LinkedHashMap<String, Object>();
        modelKv.put("top_k", Integer.valueOf(20));
        Map<String, Object> modelExtra = new LinkedHashMap<String, Object>();
        modelExtra.put("chat_template_kwargs", modelKv);
        llm.getProviders().get("demo").getModels().get(0).setExtraBody(modelExtra);

        CapturingHttp http = new CapturingHttp();
        gateway(llm, http).one(LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi"))));

        // 三个子键都在：供应商级的两个 + 模型级的一个。
        assertThat(http.body).contains("\"enable_thinking\":true");
        assertThat(http.body).contains("\"max_new_tokens\":512");
        assertThat(http.body).contains("\"top_k\":20");
    }

    /**
     * 同名子键时模型级覆盖供应商级。
     */
    @Test
    public void modelOverridesProviderOnSameLeafKey() {
        AiLiteConfig.Llm llm = llm();

        Map<String, Object> providerKv = new LinkedHashMap<String, Object>();
        providerKv.put("top_k", Integer.valueOf(10));
        Map<String, Object> providerExtra = new LinkedHashMap<String, Object>();
        providerExtra.put("chat_template_kwargs", providerKv);
        llm.getProviders().get("demo").setExtraBody(providerExtra);

        Map<String, Object> modelKv = new LinkedHashMap<String, Object>();
        modelKv.put("top_k", Integer.valueOf(99));
        Map<String, Object> modelExtra = new LinkedHashMap<String, Object>();
        modelExtra.put("chat_template_kwargs", modelKv);
        llm.getProviders().get("demo").getModels().get(0).setExtraBody(modelExtra);

        CapturingHttp http = new CapturingHttp();
        gateway(llm, http).one(LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi"))));

        assertThat(http.body).contains("\"top_k\":99");
        assertThat(http.body).doesNotContain("\"top_k\":10");
    }

    /**
     * 单次调用的字段进入请求体，且优先级最高。
     */
    @Test
    public void callLevelExtraBodyReachesRequest() {
        AiLiteConfig.Llm llm = llm();
        CapturingHttp http = new CapturingHttp();

        LlmCall call = LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi")))
                .extraBody(Collections.<String, Object>singletonMap("trace_id", "req-123"));

        gateway(llm, http).one(call);

        assertThat(http.body).contains("\"trace_id\":\"req-123\"");
    }

    /**
     * 三层同名：单次调用最优先，其次模型级，最后供应商级。
     */
    @Test
    public void callLevelWinsOverModelAndProvider() {
        AiLiteConfig.Llm llm = llm();

        Map<String, Object> providerKv = new LinkedHashMap<String, Object>();
        providerKv.put("top_k", Integer.valueOf(1));
        providerKv.put("provider_only", "p");
        Map<String, Object> providerExtra = new LinkedHashMap<String, Object>();
        providerExtra.put("chat_template_kwargs", providerKv);
        llm.getProviders().get("demo").setExtraBody(providerExtra);

        Map<String, Object> modelKv = new LinkedHashMap<String, Object>();
        modelKv.put("top_k", Integer.valueOf(2));
        modelKv.put("model_only", "m");
        Map<String, Object> modelExtra = new LinkedHashMap<String, Object>();
        modelExtra.put("chat_template_kwargs", modelKv);
        llm.getProviders().get("demo").getModels().get(0).setExtraBody(modelExtra);

        CapturingHttp http = new CapturingHttp();
        LlmCall call = LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi")))
                .extraBody(Collections.<String, Object>singletonMap(
                        "chat_template_kwargs",
                        Collections.<String, Object>singletonMap("top_k", Integer.valueOf(3))));

        gateway(llm, http).one(call);

        assertThat(http.body).contains("\"top_k\":3");
        assertThat(http.body).doesNotContain("\"top_k\":1").doesNotContain("\"top_k\":2");
        // 其它子键仍然保留。
        assertThat(http.body).contains("\"provider_only\":\"p\"");
        assertThat(http.body).contains("\"model_only\":\"m\"");
    }

    /**
     * 只设单次调用时也能生效（另外两层为空）。
     */
    @Test
    public void callLevelWorksWhenOtherLevelsEmpty() {
        AiLiteConfig.Llm llm = llm();
        CapturingHttp http = new CapturingHttp();

        gateway(llm, http).one(LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                        Collections.<LlmMessage>singletonList(LlmMessage.user("hi")))
                .extraBody(Collections.<String, Object>singletonMap("only_call", Boolean.TRUE)));

        assertThat(http.body).contains("\"only_call\":true");
    }

    /**
     * copy 与 withMessages 要带上单次 extraBody，否则步骤工厂做快照时会丢。
     */
    @Test
    public void copyKeepsCallLevelExtraBody() {
        LlmCall call = LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi")))
                .extraBody(Collections.<String, Object>singletonMap("k", "v"));

        assertThat(call.copy().getExtraBody()).containsEntry("k", "v");
        assertThat(call.withMessages(Collections.<LlmMessage>singletonList(
                LlmMessage.user("bye"))).getExtraBody()).containsEntry("k", "v");
    }

    /**
     * 合并不能改到配置对象本身，否则第二次调用会拿到被污染的值。
     */
    @Test
    public void mergingDoesNotMutateConfiguredMaps() {
        AiLiteConfig.Llm llm = llm();
        Map<String, Object> providerKv = new LinkedHashMap<String, Object>();
        providerKv.put("keep", "p");
        Map<String, Object> providerExtra = new LinkedHashMap<String, Object>();
        providerExtra.put("nested", providerKv);
        llm.getProviders().get("demo").setExtraBody(providerExtra);

        Map<String, Object> modelKv = new LinkedHashMap<String, Object>();
        modelKv.put("added", "m");
        Map<String, Object> modelExtra = new LinkedHashMap<String, Object>();
        modelExtra.put("nested", modelKv);
        llm.getProviders().get("demo").getModels().get(0).setExtraBody(modelExtra);

        CapturingHttp first = new CapturingHttp();
        gateway(llm, first).one(LlmCall.explicit("demo-chat", "demo", "qwen", "off",
                Collections.<LlmMessage>singletonList(LlmMessage.user("hi"))));

        // 配置里的供应商级 nested 仍然只有 keep，没有被混进 added。
        Map<?, ?> configured = (Map<?, ?>) llm.getProviders().get("demo")
                .getExtraBody().get("nested");
        assertThat(configured).hasSize(1);
        assertThat(configured.containsKey("keep")).isTrue();
        assertThat(configured.containsKey("added")).isFalse();
    }

    /* ---------------- 辅助 ---------------- */

    private static LlmGateway gateway(AiLiteConfig.Llm llm, HttpExecutor http) {
        ModelResolver resolver = new ModelResolver(llm, 60_000L, null);
        List<LlmTransport> transports = new ArrayList<LlmTransport>();
        transports.add(new OpenAiCompletionsTransport(http));
        return new LlmGateway(resolver, transports, new NoopLlmCallStore(),
                new DirectExecutorProvider(), null);
    }

    private static AiLiteConfig.Llm llm() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:8000/v1");
        provider.setApiKey("k");
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("qwen");
        model.setReasoning(false);
        model.setThinkingFormat("none");
        model.setContextWindow(Integer.valueOf(8192));
        model.setMaxTokens(Integer.valueOf(1000));
        provider.getModels().add(model);
        llm.getProviders().put("demo", provider);
        return llm;
    }
}

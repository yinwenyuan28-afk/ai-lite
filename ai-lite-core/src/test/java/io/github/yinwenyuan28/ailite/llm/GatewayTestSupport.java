package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 网关测试用的最小配置和假传输。
 */
public final class GatewayTestSupport {

    private GatewayTestSupport() {
    }

    public static AiLiteConfig.Llm config(String apiKey) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("biz"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(true);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));
        model.setThinkingLevelMap(Collections.singletonMap("high", "high"));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("fake");
        provider.setBaseUrl("http://localhost");
        provider.setApiKey(apiKey);
        provider.setModels(Collections.singletonList(model));

        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        profile.setThinkingLevel("high");
        llm.getProfiles().put("main", profile);
        return llm;
    }

    public static ModelResolver resolver(String apiKey) {
        return new ModelResolver(config(apiKey));
    }

    public static final class FakeTransport implements LlmTransport {

        private final LinkedList<LlmCompletion> responses = new LinkedList<LlmCompletion>();
        private final List<LlmRequest> requests = new ArrayList<LlmRequest>();

        public FakeTransport enqueue(LlmCompletion completion) {
            responses.add(completion);
            return this;
        }

        public List<LlmRequest> requests() {
            return requests;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                return new LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null);
            }
            return responses.removeFirst();
        }
    }
}

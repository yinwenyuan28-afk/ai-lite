package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 网关测试用的最小配置和假传输。
 *
 * <p><b>这个文件是 agent 模块的本地副本，不是对外产物。</b>它与
 * {@code ai-lite-llm} 的同名测试夹具内容一致，但刻意不通过 test-jar 共享：
 * 发布 test-jar 会把 {@code ai-lite-llm} 的全部测试类（150+ 个）连同这个夹具
 * 一起推到 Maven Central，而测试类不在 API 契约门禁的保护范围内——用户一旦
 * 依赖它们，一次内部重构就会让他们的构建失败，而且这些类没有任何稳定性承诺。
 *
 * <p>同包名是为了让 30 多个调用方不需要改 import。若要修改，请同时改两份；
 * 只服务于测试，漂移风险有限，这是权衡后的取舍。</p>
 */
public final class GatewayTestSupport {

    private GatewayTestSupport() {
    }

    /**
     * @param apiKey 测试用密钥
     * @return 单供应商单模型的测试配置
     */
    public static AiLiteConfig.Llm config(String apiKey) {
        return config(apiKey, null);
    }

    /**
     * @param apiKey        测试用密钥
     * @param contextWindow 模型上下文窗口；null 表示不配置（视为未知）
     * @return 单供应商单模型的测试配置
     */
    public static AiLiteConfig.Llm config(String apiKey, Integer contextWindow) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("biz"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(true);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));
        model.setContextWindow(contextWindow);
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

    /**
     * @param apiKey 测试用密钥
     * @return 解析器
     */
    public static ModelResolver resolver(String apiKey) {
        return new ModelResolver(config(apiKey));
    }

    /**
     * @param apiKey        测试用密钥
     * @param contextWindow 模型上下文窗口；null 表示不配置
     * @return 解析器
     */
    public static ModelResolver resolver(String apiKey, Integer contextWindow) {
        return new ModelResolver(config(apiKey, contextWindow));
    }

    /**
     * 记录请求、按队列返回预设响应的假传输。队列空时返回空内容。
     */
    public static final class FakeTransport implements LlmTransport {

        private final LinkedList<LlmCompletion> responses = new LinkedList<LlmCompletion>();
        private final List<LlmRequest> requests = new ArrayList<LlmRequest>();

        /**
         * @param completion 下一次调用的返回
         * @return this，便于链式排队
         */
        public FakeTransport enqueue(LlmCompletion completion) {
            responses.add(completion);
            return this;
        }

        /** @return 已收到的请求，按到达顺序 */
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

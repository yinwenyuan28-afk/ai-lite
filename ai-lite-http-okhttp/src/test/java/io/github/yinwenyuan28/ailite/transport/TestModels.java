package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;

import java.util.Collections;

/**
 * 测试用模型构造。
 */
final class TestModels {

    private TestModels() {
    }

    static ResolvedModel model(String api, String baseUrl, String thinkingFormat,
                               LlmThinkingLevel level, boolean reasoning) {
        return new ResolvedModel("test", api, baseUrl, "test-key",
                Collections.<String, String>emptyMap(), "test-model", 100000, 4096,
                true, reasoning, level, Collections.<String, String>emptyMap(),
                thinkingFormat, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), 5000, 5000);
    }
}

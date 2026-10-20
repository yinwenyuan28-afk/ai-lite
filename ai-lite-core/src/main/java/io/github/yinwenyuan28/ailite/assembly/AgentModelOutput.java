package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmResult;

/**
 * 一轮模型已经返回、工具还没执行时的助手正文。
 */
public final class AgentModelOutput {

    private final LlmResult result;
    private String content;

    AgentModelOutput(LlmResult result) {
        this.result = result;
        this.content = result == null ? "" : result.getContent();
    }

    public LlmResult getResult() {
        return result;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }
}

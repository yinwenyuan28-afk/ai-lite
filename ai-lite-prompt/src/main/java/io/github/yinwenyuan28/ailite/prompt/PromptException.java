package io.github.yinwenyuan28.ailite.prompt;

/**
 * 提示词操作失败。与 LLM 调用失败分开，便于业务分别处理。
 */
public class PromptException extends RuntimeException {

    public PromptException(String message) {
        super(message);
    }

    public PromptException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用失败时抛出的统一异常。消息里不包含提示词、图片和密钥。
 */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}

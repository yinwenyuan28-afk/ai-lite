package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用方传入的消息角色。{@link #OTHER} 在出站前会折叠成用户文本。
 */
public enum LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL_RESULT,
    OTHER
}

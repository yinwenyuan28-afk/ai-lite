package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.List;

/**
 * 把一次调用的消息和回复收成可以落库的文本。不写图片原文，也不写密钥。
 */
public final class CallBodyText {

    public static final int LIMIT = 8000;

    private CallBodyText() {
    }

    /**
     * @param messages 本轮发给模型的消息
     * @param apiKey   生效密钥，出现在正文里会被换掉
     * @return 输入文本
     */
    public static String input(List<LlmMessage> messages, String apiKey) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (LlmMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(message.getRole() == null ? "message" : message.getRole().name().toLowerCase());
            text.append(": ").append(message.getText() == null ? "" : message.getText());
            if (message.getImages() != null && !message.getImages().isEmpty()) {
                text.append(" [图片]");
            }
        }
        return clip(text.toString(), apiKey);
    }

    /**
     * @param completion 模型回复，失败且没有回复时为 null
     * @param apiKey     生效密钥
     * @return 输出文本
     */
    public static String output(LlmCompletion completion, String apiKey) {
        if (completion == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (completion.getContent() != null) {
            text.append(completion.getContent());
        }
        if (Strings.isNotBlank(completion.getThinkingText())) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("thinking: ").append(completion.getThinkingText());
        }
        return clip(text.toString(), apiKey);
    }

    static String clip(String text, String apiKey) {
        String value = text == null ? "" : text;
        if (Strings.isNotEmpty(apiKey) && value.contains(apiKey)) {
            value = value.replace(apiKey, "***");
        }
        if (value.length() > LIMIT) {
            return value.substring(0, LIMIT);
        }
        return value;
    }
}

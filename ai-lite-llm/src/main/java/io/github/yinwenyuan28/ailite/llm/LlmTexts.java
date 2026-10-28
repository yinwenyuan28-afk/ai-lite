package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息折叠、入参校验，以及把推理拼进助手正文。
 */
public final class LlmTexts {

    private LlmTexts() {
    }

    /**
     * 拼接推理和正文：两边都有时中间加一个空行；只有一边时就是那一边。
     *
     * @param reasoning 推理文本，可空
     * @param content   正文，可空
     * @return 拼接结果，不会为 null
     */
    public static String splice(String reasoning, String content) {
        boolean hasReasoning = Strings.isNotBlank(reasoning);
        boolean hasContent = Strings.isNotBlank(content);
        if (hasReasoning && hasContent) {
            return reasoning + "\n\n" + content;
        }
        if (hasReasoning) {
            return reasoning;
        }
        return content == null ? "" : content;
    }

    /**
     * 发请求前的统一校验。
     *
     * @param messages    消息列表
     * @param acceptsImage 模型是否接受图片
     * @throws LlmCallException 消息为空、工具结果缺 id、图片位置非法或模型不支持图片时抛出
     */
    public static void validate(List<LlmMessage> messages, boolean acceptsImage) {
        if (messages == null || messages.isEmpty()) {
            throw new LlmCallException("消息不能为空");
        }
        for (LlmMessage message : messages) {
            if (message == null) {
                throw new LlmCallException("消息不能为空");
            }
            if (message.getRole() == LlmRole.TOOL_RESULT && Strings.isBlank(message.getToolCallId())) {
                throw new LlmCallException("工具结果缺少 toolCallId");
            }
            if (!message.getImages().isEmpty()) {
                if (message.getRole() != LlmRole.USER && message.getRole() != LlmRole.OTHER) {
                    throw new LlmCallException("图片只能出现在用户消息");
                }
                if (!acceptsImage) {
                    throw new LlmCallException("模型不接受图片");
                }
                for (LlmImage image : message.getImages()) {
                    validateImage(image);
                }
            }
            for (LlmToolCall call : message.getToolCalls()) {
                if (call == null || Strings.isBlank(call.getId())) {
                    throw new LlmCallException("工具调用缺少 id");
                }
            }
        }
    }

    /**
     * 图片参数的统一校验：null、空 MIME、非 image/*、空 base64、非 base64 data URL
     * 都在发请求前拒绝。
     */
    private static void validateImage(LlmImage image) {
        if (image == null) {
            throw new LlmCallException("图片不能为空");
        }
        String mime = image.getMime();
        if (Strings.isBlank(mime)) {
            throw new LlmCallException("图片 MIME 不能为空");
        }
        if (!mime.regionMatches(true, 0, "image/", 0, 6)) {
            throw new LlmCallException("图片 MIME 必须以 image/ 开头: " + mime);
        }
        String data = image.getData();
        if (Strings.isBlank(data)) {
            throw new LlmCallException("图片数据不能为空");
        }
        if (data.regionMatches(true, 0, "data:", 0, 5)) {
            // 走到这里说明是 data URL，但不是 base64 形式；三个协议只能发送 base64。
            throw new LlmCallException("仅支持 base64 data URL");
        }
    }

    /**
     * 折叠消息：所有 system 合并成一条放在最前，OTHER 转成 user，其余保持原顺序。
     *
     * @param messages 原始消息
     * @return 折叠后的新列表
     */
    public static List<LlmMessage> fold(List<LlmMessage> messages) {
        if (messages == null) {
            throw new LlmCallException("消息不能为空");
        }
        StringBuilder system = new StringBuilder();
        List<LlmMessage> rest = new ArrayList<LlmMessage>();
        for (LlmMessage message : messages) {
            if (message == null) {
                throw new LlmCallException("消息不能为空");
            }
            if (message.getRole() == LlmRole.SYSTEM) {
                if (Strings.isNotBlank(message.getText())) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(message.getText());
                }
            } else if (message.getRole() == LlmRole.OTHER) {
                rest.add(LlmMessage.user(message.getText(), message.getImages()));
            } else {
                rest.add(message);
            }
        }
        List<LlmMessage> folded = new ArrayList<LlmMessage>();
        if (system.length() > 0) {
            folded.add(LlmMessage.system(system.toString()));
        }
        folded.addAll(rest);
        return folded;
    }
}

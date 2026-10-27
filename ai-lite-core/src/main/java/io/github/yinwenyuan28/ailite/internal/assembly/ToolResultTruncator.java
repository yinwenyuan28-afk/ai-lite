package io.github.yinwenyuan28.ailite.internal.assembly;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;

/**
 * 工具结果截断器。把过长的工具输出截到上限，并在末尾加明确标记。
 *
 * <p><b>为什么截断要发生在工具执行之后、钩子之前</b>：{@code AgentSessionLoop} 的
 * {@code SessionRecorder} 本身就是 {@code afterTool} 钩子，它记录的就是最终落盘内容。
 * 如果截断放在钩子之后，钩子记下的是原文，落盘的还是原文，截断等于白做。
 * 因此顺序固定为：{@code execute → truncate → afterTool → 写回上下文}。</p>
 *
 * <p><b>为什么标记里要写原始长度</b>：模型看到"被截断了多少"才知道该换策略
 * （例如改用分页参数），而不是以为工具返回的就是全部内容。</p>
 *
 * <p><b>已知边界</b>：{@code afterTool} 钩子改写后的文本不再截断。这是有意的——
 * 钩子是调用方的明确意图，框架不该二次裁剪调用方产出的内容。</p>
 */
public final class ToolResultTruncator {

    private ToolResultTruncator() {
    }

    /**
     * 按上限截断工具结果。
     *
     * <p>保留前 {@code maxChars} 个字符，再追加标记。标记写在保留内容之后，
     * 因此总长会略超上限——上限的作用是控制量级，不是做严格字节约束；
     * 这样标记里的"保留前 N"才与真实保留长度一致。</p>
     *
     * @param text     工具原始输出，可为 null
     * @param maxChars 上限字符数；小于等于 0 表示不限
     * @return 原文或截断文本，永不为 null
     */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + marker(text.length(), maxChars);
    }

    /**
     * 解析单轮覆盖值：三态语义，见 {@code ResumeOptions#getMaxToolResultChars()}。
     *
     * @param override      单轮覆盖值，可为 null
     * @param globalEnabled 全局开关
     * @param globalMaxChars 全局上限
     * @return 实际生效上限；0 表示不限
     */
    public static int resolveLimit(Integer override, boolean globalEnabled, int globalMaxChars) {
        if (override != null) {
            return override.intValue();
        }
        return globalEnabled ? globalMaxChars : 0;
    }

    /**
     * 从配置取全局截断参数。
     *
     * @param context 上下文配置，可为 null
     * @return 生效上限；0 表示不限
     */
    public static int limitFromConfig(AiLiteConfig.Context context) {
        if (context == null) {
            return 0;
        }
        AiLiteConfig.Context.ToolResult toolResult = context.getToolResult();
        if (toolResult == null || !toolResult.isEnabled()) {
            return 0;
        }
        return toolResult.getMaxChars();
    }

    private static String marker(int original, int limit) {
        return "…[输出过长已截断，原始 " + original + " 字符，保留前 " + limit + "]";
    }
}

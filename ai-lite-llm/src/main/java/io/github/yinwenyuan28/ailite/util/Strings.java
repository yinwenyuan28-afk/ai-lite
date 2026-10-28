package io.github.yinwenyuan28.ailite.util;

/**
 * 极小的字符串工具。核心模块刻意不引 commons-lang3，保持依赖面最小。
 */
public final class Strings {

    private Strings() {
    }

    /**
     * 判断字符串是否为 null、空串或只含空白。
     *
     * @param text 待判断文本
     * @return true 表示没有有效内容
     */
    public static boolean isBlank(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否包含非空白内容。
     *
     * @param text 待判断文本
     * @return true 表示有有效内容
     */
    public static boolean isNotBlank(String text) {
        return !isBlank(text);
    }

    /**
     * 判断字符串是否非 null 且非空串。空白字符算有效内容，流式增量会用到。
     *
     * @param text 待判断文本
     * @return true 表示有内容
     */
    public static boolean isNotEmpty(String text) {
        return text != null && !text.isEmpty();
    }

    /**
     * 去掉两端空白；结果为空时返回 null。
     *
     * @param text 原始文本
     * @return 去空白后的文本，或 null
     */
    public static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * null 安全的字符串兜底。
     *
     * @param text 原始文本
     * @return 原文本，或空串
     */
    public static String defaultString(String text) {
        return text == null ? "" : text;
    }
}

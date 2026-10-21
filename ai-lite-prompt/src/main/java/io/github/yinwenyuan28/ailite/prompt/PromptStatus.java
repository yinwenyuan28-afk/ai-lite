package io.github.yinwenyuan28.ailite.prompt;

/**
 * 提示词版本状态。只有草稿可以发布，只有已发布/已退役的版本可以回滚。
 */
public enum PromptStatus {

    DRAFT("draft"),
    PUBLISHED("published"),
    RETIRED("retired");

    private final String wire;

    PromptStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * @param value 持久化值
     * @return 对应状态；null 或未知值返回 null
     */
    public static PromptStatus parse(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        for (PromptStatus status : values()) {
            if (status.wire.equalsIgnoreCase(text) || status.name().equalsIgnoreCase(text)) {
                return status;
            }
        }
        return null;
    }
}

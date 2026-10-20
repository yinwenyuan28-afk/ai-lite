package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.util.Strings;

/**
 * 思考档位。名称与 pi 的 ThinkingLevel 一致。
 */
public enum LlmThinkingLevel {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String wire;

    LlmThinkingLevel(String wire) {
        this.wire = wire;
    }

    /**
     * @return 配置和记录里使用的稳定字符串
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析配置里的档位名，大小写不敏感。
     *
     * @param raw 档位名
     * @return 档位
     * @throws LlmCallException 名称为空或未知时抛出
     */
    public static LlmThinkingLevel parse(String raw) {
        if (Strings.isBlank(raw)) {
            throw new LlmCallException("思考档不能为空");
        }
        String name = raw.trim().toLowerCase();
        // YAML 会把不带引号的 off/on/yes/no 解析成布尔值，绑定到 String 后变成 true/false。
        if ("false".equals(name) || "no".equals(name) || "none".equals(name)) {
            name = "off";
        } else if ("true".equals(name) || "yes".equals(name) || "on".equals(name)) {
            name = "high";
        }
        for (LlmThinkingLevel level : values()) {
            if (level.wire.equals(name)) {
                return level;
            }
        }
        throw new LlmCallException("未知思考档: " + raw.trim());
    }
}

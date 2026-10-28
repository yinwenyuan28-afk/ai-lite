package io.github.yinwenyuan28.ailite.llm;

import java.util.Map;

/**
 * 思考档钳制。
 *
 * <p>规则：模型不支持推理或请求 off 时一律关闭；模型没有映射表时，xhigh 和 max 降到 high；
 * 有映射表时，优先用请求档，不支持就向低档回退，最后回到 off。</p>
 */
public final class LlmThinking {

    private LlmThinking() {
    }

    /**
     * 把业务请求的思考档钳到模型实际支持的档位。
     *
     * @param requested 业务请求档，可为 null
     * @param reasoning 模型是否支持推理
     * @param levelMap  档位映射；key 是业务档位，value 为 null 表示不支持
     * @return 实际生效的档位，不会为 null
     */
    public static LlmThinkingLevel clamp(LlmThinkingLevel requested, boolean reasoning,
                                         Map<String, String> levelMap) {
        if (!reasoning || requested == null || requested == LlmThinkingLevel.OFF) {
            return LlmThinkingLevel.OFF;
        }
        if (levelMap == null || levelMap.isEmpty()) {
            if (requested == LlmThinkingLevel.XHIGH || requested == LlmThinkingLevel.MAX) {
                return LlmThinkingLevel.HIGH;
            }
            return requested;
        }
        if (supported(levelMap, requested)) {
            return requested;
        }
        LlmThinkingLevel[] order = LlmThinkingLevel.values();
        for (int i = requested.ordinal() - 1; i >= 0; i--) {
            if (order[i] == LlmThinkingLevel.OFF || supported(levelMap, order[i])) {
                return order[i];
            }
        }
        return LlmThinkingLevel.OFF;
    }

    /**
     * 该档位是否要求打开思考参数。
     *
     * @param level 档位
     * @return true 表示需要向模型发送思考开关
     */
    public static boolean thinkingEnabled(LlmThinkingLevel level) {
        return level != null && level != LlmThinkingLevel.OFF;
    }

    private static boolean supported(Map<String, String> levelMap, LlmThinkingLevel level) {
        if (!levelMap.containsKey(level.wire())) {
            return false;
        }
        return levelMap.get(level.wire()) != null;
    }
}

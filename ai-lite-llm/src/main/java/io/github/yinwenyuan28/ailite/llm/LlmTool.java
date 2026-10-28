package io.github.yinwenyuan28.ailite.llm;

import java.util.Map;

/**
 * 工具 SPI。
 *
 * <p>参数结构用 {@code Map<String, Object>} 表示 JSON Schema，执行入参也是 Map，
 * 这样公开 API 不绑定任何 JSON 库，业务侧也不依赖 fastjson 或 Jackson。</p>
 *
 * <p><b>演化承诺（B 组：方法集终态）</b>：一个工具就是"名字 + 说明 + 参数 schema
 * + 执行"四件事，形状不会再变，1.0 后不再新增方法。</p>
 */
public interface LlmTool {

    /**
     * @return 工具名，必须非空且在单次调用内唯一
     */
    String name();

    /**
     * @return 给模型看的工具说明
     */
    String description();

    /**
     * 参数的 JSON Schema。没有参数时返回空 Map 即可。
     *
     * @return Schema 结构
     */
    Map<String, Object> parameters();

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数，已解析为 Map
     * @return 返回给模型的文本；null 按空串处理
     */
    String execute(Map<String, Object> arguments);
}

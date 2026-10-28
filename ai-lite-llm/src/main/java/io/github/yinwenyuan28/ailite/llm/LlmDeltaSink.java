package io.github.yinwenyuan28.ailite.llm;

/**
 * 类型化增量接收器。与 {@link LlmRequest#getOnDelta()} 并存，二者择一使用。
 *
 * <p><b>为什么需要两套</b>：{@code Consumer<String> onDelta} 是既有公开契约，
 * 它把推理内容与正文推进同一条流并插入 {@code "\n\n"} 分隔符——
 * 这个行为已有测试与用户依赖，不能改。而编排层需要区分类型才能正确投影到
 * "思考区 / 正文区"，所以另开一个类型化通道：传输层若发现本 sink 存在，
 * 就只推类型化事件（不再推分隔符）；否则退回旧的无类型推送。</p>
 *
 * <p>本接口属于框架内部协作面，不是业务扩展点。</p>
 */
public interface LlmDeltaSink {

    /**
     * 推理内容增量。
     *
     * @param text 非空增量
     */
    void reasoning(String text);

    /**
     * 正文增量。
     *
     * @param text 非空增量
     */
    void content(String text);
}

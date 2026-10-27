package io.github.yinwenyuan28.ailite.assembly;

/**
 * 流式增量的类型。
 *
 * <p><b>为什么必须区分</b>：传输层会把推理内容（reasoning）与正文（content）都推进
 * 同一条增量回调，并在两者之间插入一个 {@code "\n\n"} 分隔符。如果调用方只有一个
 * 无类型的字符串回调，就无法判断"这段文字该显示在思考区还是正文区"，
 * 而且"把全部增量拼起来恰好等于最终 content"这个断言在推理模型上必然不成立。</p>
 *
 * <p>有了类型，{@link #CONTENT} 的拼接结果恒等于 {@code LlmResult.getContent()}，
 * {@link #REASONING} 的拼接结果恒等于 {@code getThinkingText()}，两者互不污染。</p>
 */
public enum DeltaKind {

    /** 模型正文增量，拼起来等于 {@code LlmResult.getContent()}。 */
    CONTENT,

    /**
     * 推理内容增量，拼起来等于 {@code LlmResult.getThinkingText()}。
     *
     * <p>不支持推理的模型不会产生本类型增量。</p>
     */
    REASONING
}

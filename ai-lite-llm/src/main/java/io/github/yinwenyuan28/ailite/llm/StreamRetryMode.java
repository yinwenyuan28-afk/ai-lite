package io.github.yinwenyuan28.ailite.llm;

/**
 * 流式重试的窗口口径，控制「已经交付过增量之后还要不要重发」。
 *
 * <p>之所以要显式选择，是因为模型流式没有断点续传：重发的物理含义是「把同样的
 * 请求再发一遍」，服务端会从头重新生成，第一次已经交给 {@code onDelta} 的文本
 * 无法撤回。两种口径的差别只在「谁负责收拾第一次的输出」。</p>
 *
 * @see io.github.yinwenyuan28.ailite.llm.LlmGateway#oneByStreamWithRetry
 */
public enum StreamRetryMode {

    /**
     * 只在本轮尚未交付任何增量时重试（默认，外部零感知）。
     *
     * <p>一旦 {@code onDelta} 收到过内容，即使失败属于可重试类别也立刻停手，
     * 已展示内容保留、异常原样抛出。调用方不需要做任何清理。</p>
     */
    BEFORE_FIRST_DELTA,

    /**
     * 无视交付状态：只要有重试机会就整段重发。
     *
     * <p><b>调用方必须清理第一次的输出。</b>重发会再次从头推送完整内容，若不清屏，
     * 用户会看到「前段 + 重发全文」拼接在一起的重复文本。清理时机由
     * {@code onAttemptDiscarded} 通知，它在下一次尝试发起之前触发。</p>
     *
     * <p>适用场景：前端能整体替换气泡内容、或有明确的「重置本轮展示」动作。
     * 不适合 append-only 的终端或已落库的文本。</p>
     */
    ALWAYS
}

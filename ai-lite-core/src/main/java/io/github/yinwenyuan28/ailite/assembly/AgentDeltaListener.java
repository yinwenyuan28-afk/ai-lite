package io.github.yinwenyuan28.ailite.assembly;

/**
 * 同步流式增量回调。
 *
 * <p><b>为什么用单抽象方法</b>：单抽象方法接口才能写成 lambda。如果所有方法都带
 * {@code default}，调用方就必须写匿名类，这在"只想打印增量"这种最常见场景下
 * 非常啰嗦。</p>
 *
 * <p><b>线程契约</b>：{@code runStream} 的增量回调恒在调用方线程上执行。</p>
 *
 * <p><b>异常语义</b>：回调抛出的 {@code RuntimeException} 会中断本次 run，
 * 由 {@code AgentLoop} 按普通异常路径收敛（{@code afterRun} 仍会被调用一次）。
 * 需要"回调出错不影响生成"的场景请自行在实现里 catch。</p>
 */
@FunctionalInterface
public interface AgentDeltaListener {

    /**
     * 收到一段增量。
     *
     * @param round 当前轮次，从 1 开始；一次 run 有工具循环时会有多轮
     * @param kind  增量类型，见 {@link DeltaKind}
     * @param text  增量文本，永不为 null 且非空
     */
    void onDelta(int round, DeltaKind kind, String text);
}

package io.github.yinwenyuan28.ailite.llm;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 重试流程的纯函数工具。同步、协程和异步三个入口共用同一套 deadline、
 * 退避裁剪与异常聚合规则，避免三份实现逐渐漂移。
 *
 * <p>本类不持有状态、不执行 I/O、不睡眠，也不创建线程。调用方负责真正等待
 * 或调度；这里只回答“是否已过期”“还能等多久”“最终异常怎么组合”。</p>
 */
public final class RetrySupport {

    private RetrySupport() {
    }

    /**
     * 把总时限换算成绝对 deadline。
     *
     * <p>使用 {@link System#nanoTime()} 的单调时钟，不受系统时间调整影响。
     * {@code totalDeadlineMs=0} 合法，表示立即过期，与“未设置 deadline”严格区分。</p>
     *
     * @param startNanos     重试流程起点的 {@code System.nanoTime()}
     * @param totalDeadlineMs 总时限毫秒数；null 表示不限制
     * @return 绝对 deadline 纳秒；null 表示不限制
     */
    public static Long deadlineNanos(long startNanos, Long totalDeadlineMs) {
        if (totalDeadlineMs == null) {
            return null;
        }
        long millis = Math.max(0L, totalDeadlineMs.longValue());
        return Long.valueOf(startNanos + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /**
     * @param deadlineNanos 绝对 deadline；null 表示不限制
     * @param nowNanos      当前 {@code System.nanoTime()}
     * @return 是否已到达或超过 deadline
     */
    public static boolean deadlineReached(Long deadlineNanos, long nowNanos) {
        return deadlineNanos != null
                && nowNanos - deadlineNanos.longValue() >= 0L;
    }

    /**
     * @param deadlineNanos 绝对 deadline；null 表示不限制
     * @param nowNanos      当前 {@code System.nanoTime()}
     * @return 剩余纳秒；无 deadline 时为 {@link Long#MAX_VALUE}，已过期时为 0
     */
    public static long remainingNanos(Long deadlineNanos, long nowNanos) {
        if (deadlineNanos == null) {
            return Long.MAX_VALUE;
        }
        long remaining = deadlineNanos.longValue() - nowNanos;
        return remaining < 0L ? 0L : remaining;
    }

    /**
     * 把本次退避裁剪到剩余总时限内。到点后由调用方再次检查 deadline 并收敛，
     * 不会让一次退避悄悄超出总时限。
     *
     * @param delayMs       策略算出的退避毫秒数
     * @param deadlineNanos 绝对 deadline；null 表示不限制
     * @param nowNanos      当前 {@code System.nanoTime()}
     * @return 实际应等待的毫秒数
     */
    public static long clampDelayMs(long delayMs, Long deadlineNanos, long nowNanos) {
        long delay = Math.max(0L, delayMs);
        if (deadlineNanos == null) {
            return delay;
        }
        long remaining = remainingNanos(deadlineNanos, nowNanos);
        // 向上取整：宁可晚到一点让 deadline 检查收敛，也不能早到后多打一次请求。
        long remainingMs = (remaining + 999999L) / 1000000L;
        return Math.min(delay, remainingMs);
    }

    /**
     * 把之前的失败挂到最终异常上，保留每次尝试的根因。
     *
     * @param last     最终异常
     * @param failures 按发生顺序排列的失败列表，最后一个应等于 {@code last}
     * @return 同一个 {@code last} 实例，便于调用方直接抛出
     */
    public static LlmCallException withSuppressed(LlmCallException last,
                                                  List<LlmCallException> failures) {
        for (int i = 0; i < failures.size() - 1; i++) {
            LlmCallException failure = failures.get(i);
            if (failure != last) {
                last.addSuppressed(failure);
            }
        }
        return last;
    }

    /**
     * 总时限到期时构造的异常；保留最后一次失败的分类和根因。
     *
     * @param failures 已发生的失败；可为空列表
     * @return deadline 异常，附带之前的失败
     */
    public static LlmCallException deadlineFailure(List<LlmCallException> failures) {
        if (failures.isEmpty()) {
            return new LlmCallException("重试超过总时限", null,
                    LlmCallException.Kind.TRANSPORT);
        }
        LlmCallException last = failures.get(failures.size() - 1);
        LlmCallException ex = new LlmCallException("重试超过总时限", last,
                last.getKind(), last.getStatusCode());
        for (int i = 0; i < failures.size() - 1; i++) {
            ex.addSuppressed(failures.get(i));
        }
        return ex;
    }
}

package io.github.yinwenyuan28.ailite.guard;

/**
 * 出口保护指标回调。core 只暴露这个接口，默认 no-op；
 * Starter 挂 Micrometer 实现（限流熔断计划 §3.5）。
 *
 * <p>实现必须无阻塞、无异常抛出：回调在闸门锁内触发，慢回调会拖垮所有
 * 该供应商的调用方。key 值永不进指标标签。</p>
 */
public interface GuardMetrics {

    /**
     * 一次 guard 拒绝（未发出请求）。
     *
     * @param providerId 供应商 id
     * @param reason     concurrency（并发/排队）或 circuit（熔断）
     */
    void onRejected(String providerId, String reason);

    /**
     * 熔断状态变化。
     *
     * @param providerId 供应商 id
     * @param state      0=CLOSED，1=OPEN，2=HALF_OPEN
     */
    void onCircuitState(String providerId, int state);

    /**
     * 一次 429 判定（单独于失败率口径的观测点，供多 key 轮换定位）。
     *
     * @param providerId 供应商 id
     */
    void onRateLimited(String providerId);

    /** 默认实现：全部忽略。 */
    GuardMetrics NOOP = new GuardMetrics() {
        @Override
        public void onRejected(String providerId, String reason) {
        }

        @Override
        public void onCircuitState(String providerId, int state) {
        }

        @Override
        public void onRateLimited(String providerId) {
        }
    };
}

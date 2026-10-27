package io.github.yinwenyuan28.ailite.internal.session;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.util.Strings;

/**
 * 上下文预算参数与不变式校验。
 *
 * <p><b>为什么要在启动期校验</b>：{@code keepRecentTokens} 与 {@code reserveTokens}
 * 组合不当会导致确定性故障——压缩后估算值仍高于触发线，于是每轮都重新压缩、
 * 每轮都花钱。这类错误在运行期表现为"账单异常高"而不是报错，极难定位，
 * 必须在启动时用算式拦下来。</p>
 *
 * <p><b>不变式</b>：</p>
 * <ol>
 *   <li>{@code keepRecentTokens + summaryMaxTokens < contextWindow - reserveTokens}</li>
 *   <li>{@code contextWindow <= reserveTokens} → 视为窗口未知，跳过预算检查</li>
 *   <li>{@code maxPerRun >= 1}（单次 run 至少允许压缩一次，否则该开关无意义）</li>
 * </ol>
 */
public final class ContextBudget {

    private final AiLiteConfig.Context config;

    /**
     * @param config 上下文配置，null 表示全部关闭
     */
    public ContextBudget(AiLiteConfig.Context config) {
        this.config = config;
    }

    /**
     * @return 原始配置，供压缩器读取摘要模型等参数
     */
    public AiLiteConfig.Context config() {
        return config;
    }

    /**
     * @return 预算检测是否启用
     */
    public boolean budgetEnabled() {
        return config != null && config.isEnabled();
    }

    /**
     * @return 自动压缩是否启用
     */
    public boolean compactionEnabled() {
        return budgetEnabled() && config.getCompaction() != null
                && config.getCompaction().isEnabled();
    }

    /**
     * 计算触发线：窗口减去留给模型回复的余量。
     *
     * @param contextWindow 模型上下文窗口
     * @return 触发线；窗口未知时返回 0 表示不做检查
     */
    public int triggerTokens(int contextWindow) {
        if (!budgetEnabled() || contextWindow <= 0) {
            return 0;
        }
        int reserve = config.getBudget() == null ? 0 : config.getBudget().getReserveTokens();
        int trigger = contextWindow - reserve;
        // 窗口小于等于预留量时无法判断"够不够"，按未知处理。
        return trigger <= 0 ? 0 : trigger;
    }

    /**
     * 校验配置自洽性。启动期或首次使用前调用。
     *
     * @param contextWindow 模型上下文窗口；null 或 &lt;=0 表示未知，跳过校验
     * @param modelLabel    模型标识，仅用于错误消息定位
     * @throws LlmCallException 不变式不满足
     */
    public void validate(Integer contextWindow, String modelLabel) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        AiLiteConfig.Context.Compaction compaction = config.getCompaction();
        if (compaction == null || !compaction.isEnabled()) {
            return;
        }
        if (compaction.getMaxPerRun() < 1) {
            throw new LlmCallException("上下文压缩配置非法：max-per-run 必须大于等于 1");
        }
        if (compaction.getSummaryMaxTokens() <= 0) {
            throw new LlmCallException("上下文压缩配置非法：summary-max-tokens 必须大于 0");
        }
        if (compaction.getKeepRecentTokens() <= 0) {
            throw new LlmCallException("上下文压缩配置非法：keep-recent-tokens 必须大于 0");
        }
        if (contextWindow == null || contextWindow.intValue() <= 0) {
            return;
        }
        int window = contextWindow.intValue();
        int reserve = config.getBudget() == null ? 0 : config.getBudget().getReserveTokens();
        int trigger = window - reserve;
        if (trigger <= 0) {
            // 窗口未知：预算检查整体失效，压缩不会触发，配置再矛盾也无害。
            return;
        }
        int required = compaction.getKeepRecentTokens() + compaction.getSummaryMaxTokens();
        if (required >= trigger) {
            String label = Strings.isBlank(modelLabel) ? "当前模型" : modelLabel;
            throw new LlmCallException("上下文压缩配置非法（" + label + "）："
                    + "keep-recent-tokens(" + compaction.getKeepRecentTokens() + ")"
                    + " + summary-max-tokens(" + compaction.getSummaryMaxTokens() + ")"
                    + " = " + required
                    + " 必须小于 context-window(" + window + ")"
                    + " - reserve-tokens(" + reserve + ")"
                    + " = " + trigger
                    + "，否则压缩后会立刻再次超限，形成每轮重复压缩");
        }
    }

    /**
     * @return 压缩后保留的最近 token 数
     */
    public int keepRecentTokens() {
        return config == null || config.getCompaction() == null
                ? 0 : config.getCompaction().getKeepRecentTokens();
    }

    /**
     * @return 摘要正文的 token 上限
     */
    public int summaryMaxTokens() {
        return config == null || config.getCompaction() == null
                ? 0 : config.getCompaction().getSummaryMaxTokens();
    }

    /**
     * @return 单次 run 内压缩次数上限
     */
    public int maxPerRun() {
        return config == null || config.getCompaction() == null
                ? 0 : config.getCompaction().getMaxPerRun();
    }

    /**
     * @return 摘要调用超时毫秒数
     */
    public long summaryTimeoutMs() {
        return config == null || config.getCompaction() == null
                ? 60000L : config.getCompaction().getTimeoutMs();
    }
}

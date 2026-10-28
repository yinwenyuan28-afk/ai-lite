package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentDeltaListener;
import io.github.yinwenyuan28.ailite.assembly.AgentLifecycle;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次续聊的可选参数集合。不可变对象，通过 {@link #builder()} 构造。
 *
 * <p>存在的理由：{@code AgentSessionLoop.resume} 已经有 8 个位置参数，其中 5 个可以传 null，
 * 继续加能力（流式回调、工具结果截断上限、整轮超时）会退化成"一串 null 里挑一个"。
 * 本对象把这些参数收敛成一个具名集合，并且后续新增字段不改变方法签名。</p>
 *
 * <p><b>演化承诺</b>：只允许新增 builder 方法与只读 getter，不删改现有字段语义。
 * 旧 8 参重载永久保留并转调本对象，保证源码与二进制兼容。</p>
 */
public final class ResumeOptions {

    private final List<LlmTool> tools;
    private final List<AgentLifecycle> lifecycles;
    private final Integer maxRounds;
    private final LlmCancelHandle cancel;
    private final List<AgentSessionMessage> newUserTurns;
    private final Long timeoutMs;
    private final Integer maxToolResultChars;
    private final AgentDeltaListener deltaListener;

    private ResumeOptions(Builder builder) {
        this.tools = immutableCopy(builder.tools);
        this.lifecycles = immutableCopy(builder.lifecycles);
        this.maxRounds = builder.maxRounds;
        // 取消句柄是有状态的 one-shot 对象，必须原样传递，不能拷贝。
        this.cancel = builder.cancel;
        this.newUserTurns = immutableCopy(builder.newUserTurns);
        this.timeoutMs = builder.timeoutMs;
        this.maxToolResultChars = builder.maxToolResultChars;
        this.deltaListener = builder.deltaListener;
    }

    /**
     * @return 全默认参数：无工具、无额外钩子、默认轮数、无取消句柄、无新消息、无超时
     */
    public static ResumeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 工具列表，永不为 null，元素类型受泛型约束
     */
    public List<LlmTool> getTools() {
        return tools;
    }

    /**
     * @return 调用方生命周期，永不为 null
     */
    public List<AgentLifecycle> getLifecycles() {
        return lifecycles;
    }

    /**
     * @return 最大轮数；null 表示沿用网关默认值（8）
     */
    public Integer getMaxRounds() {
        return maxRounds;
    }

    /**
     * @return 取消句柄；null 表示本轮不可从外部取消
     */
    public LlmCancelHandle getCancel() {
        return cancel;
    }

    /**
     * @return 本轮新增的用户消息，永不为 null
     */
    public List<AgentSessionMessage> getNewUserTurns() {
        return newUserTurns;
    }

    /**
     * @return 整轮超时毫秒数；null 表示不限制。仅异步入口支持
     */
    public Long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * 单条工具结果的字符上限。
     *
     * <p>三态语义：(1) {@code null} 跟随全局配置 {@code ai-lite.context.tool-result.max-chars}；
     * (2) {@code 0} 本轮不限（覆盖全局关闭截断）；(3) 正数覆盖全局上限。</p>
     *
     * @return 上限字符数或 null
     */
    public Integer getMaxToolResultChars() {
        return maxToolResultChars;
    }

    /**
     * 同步流式增量回调。
     *
     * <p>非 null 时本轮走流式通道，增量恒在调用方线程回调；
     * null 时行为与之前完全一致（一次性返回）。异步入口本轮不接流式。</p>
     *
     * @return 增量回调或 null
     */
    public AgentDeltaListener getDeltaListener() {
        return deltaListener;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    /**
     * {@link ResumeOptions} 的构造器。所有字段可选，未设置的字段使用默认值。
     */
    public static final class Builder {

        private List<LlmTool> tools;
        private List<AgentLifecycle> lifecycles;
        private Integer maxRounds;
        private LlmCancelHandle cancel;
        private List<AgentSessionMessage> newUserTurns;
        private Long timeoutMs;
        private Integer maxToolResultChars;
        private AgentDeltaListener deltaListener;

        private Builder() {
        }

        /**
         * @param tools 工具列表，null 视为空
         * @return this
         */
        public Builder tools(List<LlmTool> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * @param lifecycles 调用方生命周期，null 视为空；内置 recorder 总会排在其后
         * @return this
         */
        public Builder lifecycles(List<AgentLifecycle> lifecycles) {
            this.lifecycles = lifecycles;
            return this;
        }

        /**
         * @param maxRounds 最大轮数；null 表示沿用默认值 8，必须大于 0
         * @return this
         */
        public Builder maxRounds(Integer maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        /**
         * @param cancel 取消句柄；null 表示本轮不可取消
         * @return this
         */
        public Builder cancel(LlmCancelHandle cancel) {
            this.cancel = cancel;
            return this;
        }

        /**
         * @param newUserTurns 本轮新增用户消息；null 视为空。按消息 id 幂等去重
         * @return this
         */
        public Builder newUserTurns(List<AgentSessionMessage> newUserTurns) {
            this.newUserTurns = newUserTurns;
            return this;
        }

        /**
         * @param timeoutMs 整轮超时毫秒数；null 或 &lt;=0 表示不限制。仅异步入口支持
         * @return this
         */
        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * @param maxToolResultChars 单条工具结果字符上限，三态语义见
         *                           {@link ResumeOptions#getMaxToolResultChars()}
         * @return this
         */
        public Builder maxToolResultChars(Integer maxToolResultChars) {
            this.maxToolResultChars = maxToolResultChars;
            return this;
        }

        /**
         * @param deltaListener 同步流式增量回调；null 表示一次性返回（默认）
         * @return this
         */
        public Builder deltaListener(AgentDeltaListener deltaListener) {
            this.deltaListener = deltaListener;
            return this;
        }

        /**
         * @return 不可变参数对象
         * @throws LlmCallException 参数非法
         */
        public ResumeOptions build() {
            if (maxRounds != null && maxRounds.intValue() <= 0) {
                throw new LlmCallException("最大轮数必须大于 0");
            }
            if (maxToolResultChars != null && maxToolResultChars.intValue() < 0) {
                throw new LlmCallException("工具结果上限不能为负数");
            }
            return new ResumeOptions(this);
        }
    }
}

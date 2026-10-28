package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 顺序执行在步骤边界被取消。与单次入口一致：取消是正常终态，不是重试错误。
 *
 * <p>异常消息只包含步骤序号，不复制根因消息；根因通过 {@link #getCause()} 保留。
 * {@link #getCompletedResults()} 是失败前已完成步骤的不可变快照，只用于诊断，
 * 不是断点续跑入口。</p>
 */
public final class WorkflowCancelledException extends LlmCallException {

    private final int stepIndex;
    private final List<LlmResult> completedResults;

    /**
     * @param stepIndex        取消发生在第几步，从 0 开始
     * @param completedResults 已完成步骤的结果
     */
    public WorkflowCancelledException(int stepIndex, List<LlmResult> completedResults) {
        this(stepIndex, completedResults, null);
    }

    /**
     * @param stepIndex        取消发生在第几步，从 0 开始
     * @param completedResults 已完成步骤的结果
     * @param cause            触发取消的原始异常，可为 null
     */
    public WorkflowCancelledException(int stepIndex, List<LlmResult> completedResults,
                                      Throwable cause) {
        super("Workflow 在第 " + stepIndex + " 步取消", cause, Kind.CANCELLED);
        this.stepIndex = stepIndex;
        this.completedResults = immutable(completedResults);
    }

    /**
     * @return 取消发生在第几步，从 0 开始
     */
    public int getStepIndex() {
        return stepIndex;
    }

    /**
     * @return 已完成步骤的不可变结果快照
     */
    public List<LlmResult> getCompletedResults() {
        return completedResults;
    }

    private static List<LlmResult> immutable(List<LlmResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<LlmResult>(results));
    }
}

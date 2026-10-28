package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第 N 个步骤执行失败。保留原始异常，并携带失败前已完成步骤的不可变快照。
 *
 * <p>消息只包含步骤序号，避免把提示词、图片 base64 或密钥复制进异常链。</p>
 */
public final class WorkflowStepException extends LlmCallException {

    private final int stepIndex;
    private final List<LlmResult> completedResults;

    /**
     * @param stepIndex        失败发生在第几步，从 0 开始
     * @param completedResults 失败前已完成步骤的结果
     * @param cause            步骤抛出的原始异常
     */
    public WorkflowStepException(int stepIndex, List<LlmResult> completedResults,
                                 Throwable cause) {
        super("Workflow 第 " + stepIndex + " 步执行失败", cause, Kind.UNKNOWN);
        this.stepIndex = stepIndex;
        this.completedResults = immutable(completedResults);
    }

    /**
     * @return 失败发生在第几步，从 0 开始
     */
    public int getStepIndex() {
        return stepIndex;
    }

    /**
     * @return 失败前已完成步骤的不可变结果快照
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

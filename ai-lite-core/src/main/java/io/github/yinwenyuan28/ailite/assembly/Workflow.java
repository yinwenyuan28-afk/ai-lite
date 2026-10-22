package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单次调用和简单工具循环的顺序执行器。步骤不能是 Agent loop。
 *
 * <p>Workflow 只组合、不做决策：不规划、不分支、不重试、不并行。取消只在步骤边界
 * 和结果层保证 L2；在途请求由传给步骤的 {@link LlmCancelHandle} 负责。</p>
 *
 * <p>本类无状态，可并发使用；但并发的 {@code run} 必须各自持有取消句柄。</p>
 */
public final class Workflow {

    private final LlmGateway gateway;

    public Workflow(LlmGateway gateway) {
        if (gateway == null) {
            throw new LlmCallException("网关不能为空");
        }
        this.gateway = gateway;
    }

    public LlmResult one(LlmCall call) {
        return gateway.one(call);
    }

    /**
     * 直通单次调用，并把取消句柄透传给网关。
     *
     * @param call   调用参数
     * @param cancel 取消句柄，可为 null
     * @return 网关结果
     */
    public LlmResult one(LlmCall call, LlmCancelHandle cancel) {
        return gateway.one(call, cancel);
    }

    public LlmResult oneByStream(LlmCall call, Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.oneByStream(call, onDelta, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return gateway.toolLoop(call, tools, maxRounds, null);
    }

    /**
     * 直通工具循环，并把取消句柄透传给网关。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param cancel    取消句柄，可为 null
     * @return 网关结果
     */
    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                              LlmCancelHandle cancel) {
        return gateway.toolLoop(call, tools, maxRounds, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools) {
        return toolLoop(call, tools, null);
    }

    public LlmResult toolLoopByStream(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                      Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.toolLoopByStream(call, tools, maxRounds, onDelta, cancel);
    }

    /**
     * 按写好的顺序执行步骤。放进 Agent loop 时直接拒绝，不发请求。
     *
     * @param steps 步骤列表
     * @return 每步结果，不可变列表
     */
    public List<LlmResult> run(List<?> steps) {
        return run(steps, null);
    }

    /**
     * 按写好的顺序执行步骤，并支持整体取消。
     *
     * <p>执行前先做不可变快照和整表结构校验：任何非法元素都会在第一步请求前失败，
     * 且结构错误优先于预取消。传入 null 句柄等价于不可取消。</p>
     *
     * @param steps  步骤列表
     * @param cancel 取消句柄，可为 null
     * @return 每步结果，不可变列表
     * @throws WorkflowCancelledException 取消，或某步返回 {@code ABORTED}
     * @throws WorkflowStepException      某步抛出异常或返回 null
     */
    public List<LlmResult> run(List<?> steps, LlmCancelHandle cancel) {
        if (steps == null) {
            throw new LlmCallException("Workflow 步骤不能为空");
        }
        List<Object> snapshot = new ArrayList<Object>(steps.size());
        for (Object step : steps) {
            snapshot.add(step);
        }
        validateSteps(snapshot);
        if (snapshot.isEmpty()) {
            // 空列表不检查取消：没有步骤可取消，也没有请求会发出。
            return Collections.emptyList();
        }
        if (cancel != null && cancel.isCancelled()) {
            throw new WorkflowCancelledException(0, Collections.<LlmResult>emptyList());
        }

        List<LlmResult> results = new ArrayList<LlmResult>();
        for (int i = 0; i < snapshot.size(); i++) {
            if (cancel != null && cancel.isCancelled()) {
                throw new WorkflowCancelledException(i, immutable(results));
            }
            LlmResult result;
            try {
                result = execute(snapshot.get(i), cancel);
            } catch (WorkflowCancelledException ex) {
                throw ex;
            } catch (WorkflowStepException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new WorkflowCancelledException(i, immutable(results), ex);
                }
                throw new WorkflowStepException(i, immutable(results), ex);
            }
            if (result == null) {
                throw new WorkflowStepException(i, immutable(results),
                        new LlmCallException("步骤返回了空结果"));
            }
            results.add(result);
            if (result.getStopReason() == LlmStopReason.ABORTED) {
                // ABORTED 具有权威性：即使没有句柄，也视为取消并停止后续步骤。
                throw new WorkflowCancelledException(i, immutable(results));
            }
        }
        return immutable(results);
    }

    /**
     * 执行单个步骤。
     *
     * @param step 步骤对象
     * @return 步骤结果
     */
    public LlmResult execute(Object step) {
        return execute(step, null);
    }

    /**
     * 执行单个步骤，并把取消句柄透传给内置步骤。
     *
     * @param step   步骤对象
     * @param cancel 取消句柄，可为 null
     * @return 步骤结果
     */
    public LlmResult execute(Object step, LlmCancelHandle cancel) {
        if (step == null) {
            throw new LlmCallException("步骤不能为空");
        }
        validateStep(step);
        return ((Step) step).execute(gateway, cancel);
    }

    /**
     * 单个步骤。
     *
     * <p>新增的带取消方法是 default，保持单抽象方法性质，现有自定义 Step 无需修改。
     * 如果自定义 Step 忽略 cancel，只保证步骤边界 L2，不保证在途 L1。</p>
     */
    public interface Step {

        LlmResult execute(LlmGateway gateway);

        default LlmResult execute(LlmGateway gateway, LlmCancelHandle cancel) {
            return execute(gateway);
        }
    }

    /**
     * 单次调用步骤。创建时对 {@link LlmCall} 做快照，避免调用方后续修改超时。
     *
     * @param call 调用参数
     * @return 步骤
     */
    public static Step oneStep(LlmCall call) {
        return oneStep(call, null);
    }

    /**
     * 单次调用步骤，并携带一个默认取消句柄。
     *
     * <p>通过 {@link #run(List, LlmCancelHandle)} / {@link #execute(Object, LlmCancelHandle)}
     * 执行时，若外层传入了句柄则以外层为准；否则使用这里捕获的句柄。</p>
     *
     * @param call   调用参数
     * @param cancel 默认取消句柄，可为 null
     * @return 步骤
     */
    public static Step oneStep(LlmCall call, LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall snapshot = call.copy();
        final LlmCancelHandle defaultCancel = cancel;
        return new Step() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.one(snapshot, defaultCancel);
            }

            @Override
            public LlmResult execute(LlmGateway gateway, LlmCancelHandle runCancel) {
                return gateway.one(snapshot,
                        runCancel != null ? runCancel : defaultCancel);
            }
        };
    }

    public static Step toolLoopStep(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return toolLoopStep(call, tools, maxRounds, null);
    }

    /**
     * 工具循环步骤。创建时对调用参数和工具列表做浅拷贝。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param cancel    默认取消句柄，可为 null
     * @return 步骤
     */
    public static Step toolLoopStep(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                    LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall callSnapshot = call.copy();
        final List<LlmTool> toolsSnapshot = tools == null ? null
                : Collections.unmodifiableList(new ArrayList<LlmTool>(tools));
        final LlmCancelHandle defaultCancel = cancel;
        return new Step() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.toolLoop(callSnapshot, toolsSnapshot, maxRounds, defaultCancel);
            }

            @Override
            public LlmResult execute(LlmGateway gateway, LlmCancelHandle runCancel) {
                return gateway.toolLoop(callSnapshot, toolsSnapshot, maxRounds,
                        runCancel != null ? runCancel : defaultCancel);
            }
        };
    }

    private static void validateSteps(List<?> steps) {
        for (Object step : steps) {
            validateStep(step);
        }
    }

    private static void validateStep(Object step) {
        if (step instanceof AgentLoop) {
            throw new LlmCallException("Workflow 不能放入 Agent loop");
        }
        if (!(step instanceof Step)) {
            throw new LlmCallException("Workflow 步骤只能是单次调用或简单工具循环");
        }
    }

    private static List<LlmResult> immutable(List<LlmResult> results) {
        if (results.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<LlmResult>(results));
    }
}

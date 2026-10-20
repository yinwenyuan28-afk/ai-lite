package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单次调用和简单工具循环的组合。步骤不能是 Agent loop。
 *
 * <p>业务自己的 for 可以调用这里的 one / toolLoop，但不能把 Agent loop 放进步骤。</p>
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

    public LlmResult oneByStream(LlmCall call, Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.oneByStream(call, onDelta, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return gateway.toolLoop(call, tools, maxRounds);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools) {
        return gateway.toolLoop(call, tools);
    }

    public LlmResult toolLoopByStream(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                      Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.toolLoopByStream(call, tools, maxRounds, onDelta, cancel);
    }

    /**
     * 按写好的顺序执行步骤。放进 Agent loop 时直接拒绝，不发请求。
     *
     * @param steps 步骤列表
     * @return 每步结果
     */
    public List<LlmResult> run(List<?> steps) {
        if (steps == null) {
            throw new LlmCallException("Workflow 步骤不能为空");
        }
        List<LlmResult> results = new ArrayList<LlmResult>();
        for (Object step : steps) {
            results.add(execute(step));
        }
        return results;
    }

    /**
     * 执行单个步骤。
     *
     * @param step 步骤对象
     * @return 步骤结果
     */
    public LlmResult execute(Object step) {
        if (step instanceof AgentLoop) {
            throw new LlmCallException("Workflow 不能放入 Agent loop");
        }
        if (!(step instanceof Step)) {
            throw new LlmCallException("Workflow 步骤只能是单次调用或简单工具循环");
        }
        return ((Step) step).execute(gateway);
    }

    /**
     * 单个步骤。
     */
    public interface Step {
        LlmResult execute(LlmGateway gateway);
    }

    public static Step oneStep(final LlmCall call) {
        return new Step() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.one(call);
            }
        };
    }

    public static Step toolLoopStep(final LlmCall call, final List<LlmTool> tools, final Integer maxRounds) {
        return new Step() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.toolLoop(call, tools, maxRounds);
            }
        };
    }
}

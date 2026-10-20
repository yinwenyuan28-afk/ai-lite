package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带上下文和生命周期的完整工具循环。每一轮只调网关的 round。
 *
 * <p>这一版是阻塞的：一轮还没返回时不看取消；返回之后如果已取消，就不再发下一轮。</p>
 */
public final class AgentLoop {

    private final LlmGateway gateway;

    public AgentLoop(LlmGateway gateway) {
        if (gateway == null) {
            throw new LlmCallException("网关不能为空");
        }
        this.gateway = gateway;
    }

    /**
     * 跑一次 Agent 循环。
     *
     * @param call       调用参数
     * @param tools      工具列表
     * @param lifecycles 生命周期，按顺序执行，可为 null
     * @param maxRounds  最大轮数，null 表示 8
     * @param cancel     取消句柄，可为 null
     * @return 最后一轮结果
     */
    public LlmResult run(LlmCall call, List<LlmTool> tools, List<AgentLifecycle> lifecycles,
                         Integer maxRounds, LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        int limit = maxRounds == null ? LlmGateway.DEFAULT_MAX_ROUNDS : maxRounds.intValue();
        if (limit <= 0) {
            throw new LlmCallException("最大轮数必须大于 0");
        }
        List<AgentLifecycle> hooks = lifecycles == null
                ? new ArrayList<AgentLifecycle>() : lifecycles;
        AgentLoopContext ctx = AgentLoopContext.copy(call.getBizId(), call.getMessages(), tools, cancel);
        eachBeforeRun(hooks, ctx);

        int completed = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;
        long latencyMs = 0L;
        LlmResult last = null;

        while (completed < limit) {
            if (ctx.isCancelled()) {
                return finish(hooks, ctx, aborted(last, inputTokens, outputTokens, totalTokens,
                        latencyMs, completed));
            }
            eachBeforeModel(hooks, ctx);
            LlmResult round = gateway.round(call.withMessages(ctx.getMessages()), ctx.getTools());
            completed++;
            inputTokens += round.getUsage().getInputTokens();
            outputTokens += round.getUsage().getOutputTokens();
            totalTokens += round.getUsage().getTotalTokens();
            latencyMs += round.getLatencyMs();

            AgentModelOutput output = new AgentModelOutput(round);
            eachAfterModel(hooks, ctx, output);
            LlmResult shown = withContent(round, output.getContent());
            boolean toolUse = shown.getStopReason() == LlmStopReason.TOOL_USE
                    && !shown.getToolCalls().isEmpty();
            boolean lastRound = completed >= limit;
            if (toolUse && !lastRound) {
                runTools(ctx, hooks, shown);
            }

            boolean stop = false;
            boolean again = false;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                TurnDecision decision = hook.afterTurn(ctx, shown);
                TurnDecision.Kind kind = decision == null
                        ? TurnDecision.Kind.MODEL_DECIDES : decision.kind();
                if (kind == TurnDecision.Kind.STOP) {
                    stop = true;
                } else if (kind == TurnDecision.Kind.CONTINUE) {
                    again = true;
                }
            }
            last = shown;
            if (ctx.isCancelled()) {
                return finish(hooks, ctx, accumulate(shown, LlmStopReason.ABORTED,
                        inputTokens, outputTokens, totalTokens, latencyMs, completed));
            }
            if (toolUse && lastRound) {
                return finish(hooks, ctx, accumulate(shown, LlmStopReason.MAX_ROUNDS,
                        inputTokens, outputTokens, totalTokens, latencyMs, completed));
            }
            if (stop) {
                return finish(hooks, ctx, accumulate(shown, LlmStopReason.STOP,
                        inputTokens, outputTokens, totalTokens, latencyMs, completed));
            }
            if ((again || toolUse) && completed < limit) {
                continue;
            }
            return finish(hooks, ctx, accumulate(shown, shown.getStopReason(),
                    inputTokens, outputTokens, totalTokens, latencyMs, completed));
        }
        return finish(hooks, ctx, aborted(last, inputTokens, outputTokens, totalTokens, latencyMs, completed));
    }

    private static void runTools(AgentLoopContext ctx, List<AgentLifecycle> hooks, LlmResult shown) {
        List<LlmToolCall> calls = shown.getToolCalls();
        ctx.getMessages().add(LlmMessage.assistant(shown.getContent(), calls));
        for (LlmToolCall call : calls) {
            AgentToolCall mutable = new AgentToolCall(call.getId(), call.getName(), call.getArgumentsJson());
            String denial = null;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                ToolDecision decision = hook.beforeTool(ctx, mutable);
                if (decision == null) {
                    decision = ToolDecision.allow();
                }
                if (!decision.isAllowed()) {
                    denial = Strings.isBlank(decision.getReason())
                            ? "已拒绝这次工具调用" : decision.getReason();
                    break;
                }
                if (decision.getArgumentsJson() != null) {
                    mutable.setArgumentsJson(decision.getArgumentsJson());
                }
            }
            String text = denial != null ? denial : execute(ctx.getTools(), mutable);
            if (denial == null) {
                for (AgentLifecycle hook : hooks) {
                    if (hook == null) {
                        continue;
                    }
                    String changed = hook.afterTool(ctx, mutable, text);
                    if (changed != null) {
                        text = changed;
                    }
                }
            }
            ctx.getMessages().add(LlmMessage.toolResult(call.getId(), text));
        }
    }

    private static String execute(List<LlmTool> tools, AgentToolCall call) {
        LlmTool tool = null;
        if (tools != null) {
            for (LlmTool candidate : tools) {
                if (candidate != null && candidate.name().equals(call.getName())) {
                    tool = candidate;
                    break;
                }
            }
        }
        if (tool == null) {
            return "未找到工具: " + Strings.defaultString(call.getName());
        }
        Map<String, Object> arguments;
        try {
            String json = call.getArgumentsJson();
            arguments = Strings.isBlank(json)
                    ? new LinkedHashMap<String, Object>() : AiLiteJson.readMap(json);
        } catch (RuntimeException ex) {
            return "工具参数不是合法 JSON";
        }
        try {
            String text = tool.execute(arguments);
            return text == null ? "" : text;
        } catch (RuntimeException ex) {
            return "工具执行失败";
        }
    }

    private static void eachBeforeRun(List<AgentLifecycle> hooks, AgentLoopContext ctx) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.beforeRun(ctx);
            }
        }
    }

    private static void eachBeforeModel(List<AgentLifecycle> hooks, AgentLoopContext ctx) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.beforeModel(ctx);
            }
        }
    }

    private static void eachAfterModel(List<AgentLifecycle> hooks, AgentLoopContext ctx,
                                       AgentModelOutput output) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.afterModel(ctx, output);
            }
        }
    }

    private static LlmResult finish(List<AgentLifecycle> hooks, AgentLoopContext ctx, LlmResult result) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.afterRun(ctx, result);
            }
        }
        return result;
    }

    private static LlmResult aborted(LlmResult last, int inputTokens, int outputTokens,
                                     int totalTokens, long latencyMs, int rounds) {
        if (last == null) {
            return new LlmResult("", null, LlmStopReason.ABORTED, LlmUsage.zero(),
                    null, null, null, latencyMs, rounds, null);
        }
        return accumulate(last, LlmStopReason.ABORTED, inputTokens, outputTokens, totalTokens,
                latencyMs, rounds);
    }

    private static LlmResult accumulate(LlmResult shown, LlmStopReason stop, int inputTokens,
                                        int outputTokens, int totalTokens, long latencyMs, int rounds) {
        return new LlmResult(shown.getContent(), shown.getThinkingText(), stop,
                LlmUsage.of(inputTokens, outputTokens, totalTokens),
                shown.getProvider(), shown.getModelId(), shown.getThinkingLevel(),
                latencyMs, rounds, shown.getToolCalls());
    }

    private static LlmResult withContent(LlmResult source, String content) {
        return new LlmResult(content, source.getThinkingText(), source.getStopReason(),
                source.getUsage(), source.getProvider(), source.getModelId(),
                source.getThinkingLevel(), source.getLatencyMs(), source.getRounds(),
                source.getToolCalls());
    }
}

package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两段式决策函数（decideToolPhase / decideTerminal）的终态矩阵单测。
 *
 * <p>同步与异步循环共用这两个纯函数，矩阵在这里锁死，任何一侧的分叉都会在此暴露。</p>
 */
public class AgentLoopDecisionTest {

    private static final LlmStopReason[] REASONS = {
            LlmStopReason.STOP, LlmStopReason.TOOL_USE, LlmStopReason.ERROR,
            LlmStopReason.ABORTED, LlmStopReason.LENGTH
    };

    @Test
    public void toolPhaseMatrixMatchesContract() {
        for (LlmStopReason reason : REASONS) {
            for (boolean hasToolCalls : new boolean[] {true, false}) {
                for (boolean lastRound : new boolean[] {true, false}) {
                    for (boolean cancelled : new boolean[] {true, false}) {
                        assertToolPhase(reason, hasToolCalls, lastRound, cancelled);
                    }
                }
            }
        }
    }

    /** 按契约逐格核对工具阶段：取消/ABORTED > 执行 > 按轮数/ERROR 合成 > 直通。 */
    private static void assertToolPhase(LlmStopReason reason, boolean hasToolCalls,
                                        boolean lastRound, boolean cancelled) {
        AgentLoop.ToolPhase phase = AgentLoop.decideToolPhase(cancelled, reason,
                hasToolCalls, lastRound);
        AgentLoop.ToolPhase expected;
        if (cancelled || reason == LlmStopReason.ABORTED) {
            expected = AgentLoop.ToolPhase.ABORTED;
        } else {
            boolean runnable = hasToolCalls && reason != LlmStopReason.ERROR;
            if (runnable && !lastRound) {
                expected = AgentLoop.ToolPhase.EXECUTE_TOOLS;
            } else if (hasToolCalls && lastRound) {
                expected = AgentLoop.ToolPhase.SYNTHESIZE_MAX_ROUNDS;
            } else if (hasToolCalls) {
                expected = AgentLoop.ToolPhase.SYNTHESIZE_NOT_RUN;
            } else {
                expected = AgentLoop.ToolPhase.PASS;
            }
        }
        assertThat(phase)
                .as("toolPhase(cancelled=%s, reason=%s, hasToolCalls=%s, lastRound=%s)",
                        cancelled, reason, hasToolCalls, lastRound)
                .isEqualTo(expected);
    }

    @Test
    public void terminalMatrixMatchesContract() {
        for (boolean cancelled : new boolean[] {true, false}) {
            for (boolean stop : new boolean[] {true, false}) {
                for (boolean lastRound : new boolean[] {true, false}) {
                    for (boolean hasToolCalls : new boolean[] {true, false}) {
                        for (boolean again : new boolean[] {true, false}) {
                            for (boolean runnable : new boolean[] {true, false}) {
                                assertTerminal(cancelled, stop, lastRound, hasToolCalls, again, runnable);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 按契约逐格核对终态：取消 > STOP > MAX_ROUNDS > 下一轮 > 模型自身 stopReason。 */
    private static void assertTerminal(boolean cancelled, boolean stop, boolean lastRound,
                                       boolean hasToolCalls, boolean again, boolean runnable) {
        AgentLoop.Terminal terminal = AgentLoop.decideTerminal(cancelled, stop, lastRound,
                hasToolCalls, again, runnable);
        AgentLoop.Terminal expected;
        if (cancelled) {
            expected = AgentLoop.Terminal.ABORTED;
        } else if (stop) {
            expected = AgentLoop.Terminal.STOP;
        } else if (lastRound) {
            expected = (hasToolCalls || again) ? AgentLoop.Terminal.MAX_ROUNDS
                    : AgentLoop.Terminal.MODEL;
        } else if (again || runnable) {
            expected = AgentLoop.Terminal.NEXT_ROUND;
        } else {
            expected = AgentLoop.Terminal.MODEL;
        }
        assertThat(terminal)
                .as("terminal(cancelled=%s, stop=%s, lastRound=%s, hasToolCalls=%s, again=%s, runnable=%s)",
                        cancelled, stop, lastRound, hasToolCalls, again, runnable)
                .isEqualTo(expected);
    }
}

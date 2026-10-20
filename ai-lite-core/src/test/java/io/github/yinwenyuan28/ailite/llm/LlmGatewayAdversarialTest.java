package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关的对抗性用例：坏 biz-id、坏消息、坏工具、轮数边界、取消和错误脱敏。
 */
public class LlmGatewayAdversarialTest {

    private static final List<LlmMessage> HELLO =
            Collections.singletonList(LlmMessage.user("hi"));

    // -------------------------------------------------------------- biz id

    @Test
    public void bizIdIsTrimmedBeforeLookupButIsCaseSensitive() {
        final CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        gateway.one(LlmCall.explicit("  biz  ", "test", "m", null, HELLO));

        assertThat(transport.requests).hasSize(1);

        assertThatThrownBy(() -> gateway.one(LlmCall.explicit("BIZ", "test", "m", null, HELLO)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void blankBizIdIsRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(LlmCall.explicit("   ", "test", "m", null, HELLO)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 不能为空");
    }

    @Test
    public void nullCallIsRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用不能为空");
    }

    // ------------------------------------------------------------- messages

    @Test
    public void emptyMessagesAreRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, Collections.<LlmMessage>emptyList())))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("消息不能为空");
    }

    @Test
    public void nullMessageListShouldFailWithLlmCallException() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("消息不能为空");
    }

    @Test
    public void nullMessageElementShouldFailWithLlmCallException() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, Arrays.asList(null, LlmMessage.user("hi")))))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("消息不能为空");
    }

    @Test
    public void onlySystemMessageIsAcceptedAndSent() {
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        gateway.one(LlmCall.explicit("biz", "test", "m", null,
                Collections.singletonList(LlmMessage.system("rules"))));

        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).getMessages()).hasSize(1);
        assertThat(transport.requests.get(0).getMessages().get(0).getRole()).isEqualTo(LlmRole.SYSTEM);
    }

    @Test
    public void toolResultWithoutToolCallIdIsRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(LlmCall.explicit("biz", "test", "m", null,
                Arrays.asList(LlmMessage.user("hi"), LlmMessage.toolResult("  ", "x")))))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具结果缺少 toolCallId");
    }

    @Test
    public void multipleSystemMessagesAreFoldedAndOtherBecomesUser() {
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        gateway.one(LlmCall.explicit("biz", "test", "m", null, Arrays.asList(
                LlmMessage.system("a"),
                LlmMessage.user("u"),
                LlmMessage.system("b"),
                LlmMessage.other("o"))));

        List<LlmMessage> sent = transport.requests.get(0).getMessages();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).getRole()).isEqualTo(LlmRole.SYSTEM);
        assertThat(sent.get(0).getText()).isEqualTo("a\n\nb");
        assertThat(sent.get(1).getRole()).isEqualTo(LlmRole.USER);
        assertThat(sent.get(2).getRole()).isEqualTo(LlmRole.USER);
        assertThat(sent.get(2).getText()).isEqualTo("o");
    }

    @Test
    public void imageOnNonImageModelIsRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.one(LlmCall.explicit("biz", "test", "m", null,
                Collections.singletonList(LlmMessage.user("look",
                        Collections.singletonList(new LlmImage("image/png", "AAAA")))))))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型不接受图片");
    }

    // ---------------------------------------------------------------- tools

    @Test
    public void nullOrEmptyToolListInToolLoopIsRejected() {
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO), null, Integer.valueOf(2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具列表不能为空");
        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.<LlmTool>emptyList(), Integer.valueOf(2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具列表不能为空");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void duplicateBlankAndNullToolNamesAreRejected() {
        LlmGateway gateway = gateway(new CapturingTransport(), new RecordingStore());

        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Arrays.asList(tool("echo", new int[1]), tool("echo", new int[1])), Integer.valueOf(2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具名称重复");

        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("   ", new int[1])), Integer.valueOf(2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具名称不能为空");

        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.<LlmTool>singletonList(null), Integer.valueOf(2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具名称不能为空");
    }

    @Test
    public void invalidToolArgumentsAreReportedBackToModel() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "echo", "{not-json")))
                .enqueue(stop("done"));
        LlmGateway gateway = gateway(transport, new RecordingStore());

        LlmResult result = gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(4));

        assertThat(result.getContent()).isEqualTo("done");
        LlmMessage toolResult = lastMessage(transport.requests.get(1));
        assertThat(toolResult.getRole()).isEqualTo(LlmRole.TOOL_RESULT);
        assertThat(toolResult.getText()).isEqualTo("工具参数不是合法 JSON");
    }

    @Test
    public void throwingToolBecomesTextInsteadOfBreakingTheLoop() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "boom", "{}")))
                .enqueue(stop("done"));
        LlmGateway gateway = gateway(transport, new RecordingStore());
        LlmTool boom = new LlmTool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "boom";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                throw new IllegalStateException("kaboom");
            }
        };

        LlmResult result = gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(boom), Integer.valueOf(4));

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(lastMessage(transport.requests.get(1)).getText()).isEqualTo("工具执行失败");
    }

    @Test
    public void nullToolResultIsNormalizedToEmptyString() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "nullTool", "{}")))
                .enqueue(stop("done"));
        LlmGateway gateway = gateway(transport, new RecordingStore());
        LlmTool nullTool = new LlmTool() {
            @Override
            public String name() {
                return "nullTool";
            }

            @Override
            public String description() {
                return "null";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return null;
            }
        };

        gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(nullTool), Integer.valueOf(4));

        assertThat(lastMessage(transport.requests.get(1)).getText()).isEmpty();
    }

    @Test
    public void unknownToolNameIsReportedBackToModel() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "ghost", "{}")))
                .enqueue(stop("done"));
        LlmGateway gateway = gateway(transport, new RecordingStore());

        gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(4));

        assertThat(lastMessage(transport.requests.get(1)).getText()).isEqualTo("未找到工具: ghost");
    }

    // --------------------------------------------------------------- rounds

    @Test
    public void nonPositiveMaxRoundsIsRejectedBeforeAnyRequest() {
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(0)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("最大轮数必须大于 0");
        assertThatThrownBy(() -> gateway.toolLoop(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(-3)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("最大轮数必须大于 0");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void oneRoundLimitDoesNotExecuteToolsAndReturnsMaxRounds() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "echo", "{}")));
        LlmGateway gateway = gateway(transport, new RecordingStore());
        int[] executions = new int[1];

        LlmResult result = gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", executions)), Integer.valueOf(1));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(executions[0]).isZero();
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void modelThatAlwaysWantsToolsStopsAtLimit() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "echo", "{}")))
                .enqueue(toolUse(new LlmToolCall("c2", "echo", "{}")))
                .enqueue(toolUse(new LlmToolCall("c3", "echo", "{}")));
        LlmGateway gateway = gateway(transport, new RecordingStore());

        LlmResult result = gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(2));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void toolUseStopWithoutCallsDoesNotStartAnotherRound() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE,
                        LlmUsage.zero(), Collections.<LlmToolCall>emptyList()));
        LlmGateway gateway = gateway(transport, new RecordingStore());

        LlmResult result = gateway.toolLoop(LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", new int[1])), Integer.valueOf(4));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).isEmpty();
        assertThat(transport.requests).hasSize(1);
    }

    // --------------------------------------------------------------- cancel

    @Test
    public void cancelBeforeCallReturnsAbortedWithoutSending() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport, new RecordingStore());

        LlmResult result = gateway.oneByStream(
                LlmCall.explicit("biz", "test", "m", null, HELLO), null, cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void cancelTriggeredByFirstResponseStopsBeforeSecondRequest() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        final int[] executions = new int[1];
        CapturingTransport transport = new CapturingTransport() {
            @Override
            public LlmCompletion complete(LlmRequest request) {
                requests.add(request);
                CancellingCompletion completion = new CancellingCompletion(cancel,
                        toolUse(new LlmToolCall("c1", "echo", "{}")));
                return completion.complete(request);
            }
        };
        LlmGateway gateway = gateway(transport, new RecordingStore());

        LlmResult result = gateway.toolLoopByStream(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(tool("echo", executions)), Integer.valueOf(3),
                null, cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(transport.requests).hasSize(1);
        // 当前实现会先把本轮工具跑完，再检查取消。
        assertThat(executions[0]).isEqualTo(1);
    }

    @Test
    public void cancelDuringToolExecutionStopsBeforeSecondRequest() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        CapturingTransport transport = new CapturingTransport()
                .enqueue(toolUse(new LlmToolCall("c1", "cancelling", "{}")))
                .enqueue(stop("should-not-be-sent"));
        LlmGateway gateway = gateway(transport, new RecordingStore());
        final int[] executions = new int[1];
        LlmTool cancelling = new LlmTool() {
            @Override
            public String name() {
                return "cancelling";
            }

            @Override
            public String description() {
                return "cancel";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                executions[0]++;
                cancel.cancel();
                return "cancelled";
            }
        };

        LlmResult result = gateway.toolLoopByStream(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                Collections.singletonList(cancelling), Integer.valueOf(3), null, cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(executions[0]).isEqualTo(1);
        assertThat(transport.requests).hasSize(1);
    }

    // ------------------------------------------------------ transport errors

    @Test
    public void unregisteredTransportApiIsRejected() {
        AiLiteConfig.Llm config = config("ghost-api");
        LlmGateway gateway = new LlmGateway(new ModelResolver(config),
                Collections.<LlmTransport>singletonList(new CapturingTransport()),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, HELLO)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不支持的协议: ghost-api");
    }

    @Test
    public void deltaCallbackExceptionIsWrappedAsLlmCallException() {
        LlmTransport exploding = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                request.getOnDelta().accept("chunk");
                return stop("done");
            }
        };
        LlmGateway gateway = gateway(exploding, new RecordingStore());

        assertThatThrownBy(() -> gateway.oneByStream(
                LlmCall.explicit("biz", "test", "m", null, HELLO),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String delta) {
                        throw new IllegalStateException("consumer exploded");
                    }
                }, null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败");
    }

    @Test
    public void apiKeyInErrorMessageIsRedactedFromCallRecord() {
        final String secret = "sekret-key-9f3a";
        RecordingStore store = new RecordingStore();
        LlmTransport failing = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new LlmCallException("upstream rejected Authorization Bearer " + secret);
            }
        };
        LlmGateway gateway = new LlmGateway(new ModelResolver(config("fake", secret)),
                Collections.singletonList(failing), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, HELLO)))
                .isInstanceOf(LlmCallException.class);

        assertThat(store.records).hasSize(1);
        LlmCallRecord row = store.records.get(0);
        assertThat(row.getErrorBrief()).doesNotContain(secret);
        assertThat(row.getErrorBrief()).contains("***");
        assertThat(row.getStopReason()).isEqualTo(LlmStopReason.ERROR.wire());
        assertThat(row.getSuccess()).isEqualTo(Integer.valueOf(0));
    }

    @Test
    public void veryLongErrorMessageIsTruncatedTo200CharsWithoutNewlines() {
        final StringBuilder message = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            message.append(i % 50 == 0 ? '\n' : 'x');
        }
        RecordingStore store = new RecordingStore();
        LlmTransport failing = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new LlmCallException(message.toString());
            }
        };
        LlmGateway gateway = new LlmGateway(new ModelResolver(config("fake", "key")),
                Collections.singletonList(failing), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        assertThatThrownBy(() -> gateway.one(
                LlmCall.explicit("biz", "test", "m", null, HELLO)))
                .isInstanceOf(LlmCallException.class);

        LlmCallRecord row = store.records.get(0);
        assertThat(row.getErrorBrief()).hasSize(200);
        assertThat(row.getErrorBrief()).doesNotContain("\n").doesNotContain("\r");
    }

    // -------------------------------------------------------------- wiring

    @Test
    public void duplicateAndBlankTransportRegistrationsAreHandled() {
        assertThatThrownBy(() -> new LlmGateway(new ModelResolver(config("fake")),
                Arrays.<LlmTransport>asList(new CapturingTransport(), new CapturingTransport()),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("协议适配器重复");

        LlmTransport blank = new LlmTransport() {
            @Override
            public String api() {
                return "  ";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return stop("bad");
            }
        };
        LlmGateway gateway = new LlmGateway(new ModelResolver(config("fake")),
                Arrays.asList(blank, null, new CapturingTransport()),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());
        assertThat(gateway.one(LlmCall.explicit("biz", "test", "m", null, HELLO)).getContent())
                .isEqualTo("ok");
    }

    // -------------------------------------------------------------- helpers

    private static LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(new ModelResolver(config("fake")),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static AiLiteConfig.Llm config(String api) {
        return config(api, "key");
    }

    private static AiLiteConfig.Llm config(String api, String apiKey) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(new ArrayList<String>(Collections.singletonList("biz")));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(true);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(1024));
        model.setThinkingLevelMap(Collections.singletonMap("high", "high"));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi(api);
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        provider.setApiKey(apiKey);
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        profile.setThinkingLevel("off");
        llm.getProfiles().put("main", profile);
        return llm;
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static LlmCompletion toolUse(LlmToolCall call) {
        return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(call));
    }

    private static LlmMessage lastMessage(LlmRequest request) {
        List<LlmMessage> messages = request.getMessages();
        return messages.get(messages.size() - 1);
    }

    private static LlmTool tool(final String name, final int[] executions) {
        return new LlmTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                executions[0]++;
                return "ok";
            }
        };
    }

    /**
     * 可编排返回值的假传输。
     */
    static class CapturingTransport implements LlmTransport {
        final List<LlmRequest> requests = new ArrayList<LlmRequest>();
        private final List<LlmCompletion> responses = new ArrayList<LlmCompletion>();

        CapturingTransport enqueue(LlmCompletion completion) {
            responses.add(completion);
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                return stop("ok");
            }
            return responses.remove(0);
        }
    }

    /**
     * 返回预设结果的同时触发取消。用于模拟“首轮响应后用户取消”。
     */
    static final class CancellingCompletion {
        private final LlmCancelHandle cancel;
        private final LlmCompletion completion;

        CancellingCompletion(LlmCancelHandle cancel, LlmCompletion completion) {
            this.cancel = cancel;
            this.completion = completion;
        }

        LlmCompletion complete(LlmRequest request) {
            cancel.cancel();
            return completion;
        }
    }

    /**
     * 同步记录调用记录，便于断言脱敏和截断。
     */
    static final class RecordingStore implements LlmCallStore {
        final List<LlmCallRecord> records = new ArrayList<LlmCallRecord>();

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record);
        }
    }
}

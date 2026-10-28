package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 单次模型调用入口的图片消息 parity：同步与异步必须发送相同的 MIME、base64 和顺序。
 */
public class LlmGatewayImageTest {

    private static final LlmImage PNG = new LlmImage("image/png", "AAAA");
    private static final LlmImage JPEG = new LlmImage("image/jpeg", "BBBB");
    private static final LlmImage WEBP = new LlmImage("image/webp", "CCCC");

    @Test
    public void syncAndAsyncSendIdenticalImageMessages() throws Exception {
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("describe", Arrays.asList(PNG, JPEG)));
        CapturingTransport syncTransport = new CapturingTransport();
        CapturingTransport asyncTransport = new CapturingTransport();

        gateway(imageResolver(), syncTransport).one(call(messages));
        gateway(imageResolver(), asyncTransport).oneAsync(call(messages))
                .get(5, TimeUnit.SECONDS);

        LlmMessage syncMessage = syncTransport.requests.get(0).getMessages().get(0);
        LlmMessage asyncMessage = asyncTransport.requests.get(0).getMessages().get(0);
        assertThat(syncMessage.getRole()).isEqualTo(asyncMessage.getRole());
        assertThat(syncMessage.getText()).isEqualTo(asyncMessage.getText());
        assertThat(syncMessage.getImages()).hasSameSizeAs(asyncMessage.getImages());
        for (int i = 0; i < syncMessage.getImages().size(); i++) {
            assertThat(syncMessage.getImages().get(i).getMime())
                    .isEqualTo(asyncMessage.getImages().get(i).getMime());
            assertThat(syncMessage.getImages().get(i).getData())
                    .isEqualTo(asyncMessage.getImages().get(i).getData());
        }
    }

    @Test
    public void imageOnlyMessageIsAccepted() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("", Collections.singletonList(PNG)));

        LlmResult result = gateway(imageResolver(), transport).oneAsync(call(messages))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        LlmMessage sent = transport.requests.get(0).getMessages().get(0);
        assertThat(sent.getText()).isEmpty();
        assertThat(sent.getImages()).hasSize(1);
        assertThat(sent.getImages().get(0).getMime()).isEqualTo("image/png");
    }

    @Test
    public void multipleImagesKeepCallerOrder() {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("look", Arrays.asList(WEBP, PNG, JPEG)));

        gateway(imageResolver(), transport).one(call(messages));

        List<LlmImage> sent = transport.requests.get(0).getMessages().get(0).getImages();
        assertThat(sent).extracting("mime")
                .containsExactly("image/webp", "image/png", "image/jpeg");
        assertThat(sent).extracting("data")
                .containsExactly("CCCC", "AAAA", "BBBB");
    }

    @Test
    public void otherRoleWithImagesIsFoldedIntoUser() {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.other("look", Collections.singletonList(PNG)));

        gateway(imageResolver(), transport).one(call(messages));

        LlmMessage sent = transport.requests.get(0).getMessages().get(0);
        assertThat(sent.getRole()).isEqualTo(LlmRole.USER);
        assertThat(sent.getImages()).hasSize(1);
        assertThat(sent.getImages().get(0).getData()).isEqualTo("AAAA");
    }

    @Test
    public void textOnlyModelRejectsImageBeforeAnyRequest() {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("describe", Collections.singletonList(PNG)));

        assertThatThrownBy(() -> gateway(GatewayTestSupport.resolver("key"), transport)
                .one(call(messages)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型不接受图片");
        assertThatThrownBy(() -> gateway(GatewayTestSupport.resolver("key"), transport)
                .oneAsync(call(messages)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型不接受图片");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void syncImageOnlyMessageIsAccepted() {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("", Collections.singletonList(PNG)));

        LlmResult result = gateway(imageResolver(), transport).one(call(messages));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        LlmMessage sent = transport.requests.get(0).getMessages().get(0);
        assertThat(sent.getText()).isEmpty();
        assertThat(sent.getImages()).hasSize(1);
    }

    @Test
    public void dataUrlInputReachesTransportAsRawBase64() {
        CapturingTransport transport = new CapturingTransport();
        List<LlmMessage> messages = Collections.singletonList(
                LlmMessage.user("look", Collections.singletonList(
                        new LlmImage("image/png", "data:image/png;base64,AAAA"))));

        gateway(imageResolver(), transport).one(call(messages));

        assertThat(transport.requests.get(0).getMessages().get(0)
                .getImages().get(0).getData()).isEqualTo("AAAA");
    }

    @Test
    public void invalidImageFailsBeforeRequestOnBothPaths() {
        List<LlmImage> invalid = Arrays.asList(
                null,
                new LlmImage("  ", "AAAA"),
                new LlmImage("text/plain", "AAAA"),
                new LlmImage("image/png", " "),
                new LlmImage("image/svg+xml", "data:image/svg+xml;charset=utf-8,<svg/>"));
        for (LlmImage image : invalid) {
            CapturingTransport transport = new CapturingTransport();
            List<LlmMessage> messages = Collections.singletonList(
                    LlmMessage.user("look", Collections.singletonList(image)));

            assertThatThrownBy(() -> gateway(imageResolver(), transport).one(call(messages)))
                    .isInstanceOf(LlmCallException.class);
            assertThatThrownBy(() -> gateway(imageResolver(), transport)
                    .oneAsync(call(messages)))
                    .isInstanceOf(LlmCallException.class);
            assertThat(transport.requests).isEmpty();
        }
    }

    private static LlmCall call(List<LlmMessage> messages) {
        return LlmCall.profile("biz", "main", messages);
    }

    private static ModelResolver imageResolver() {
        AiLiteConfig.Llm llm = GatewayTestSupport.config("key");
        AiLiteConfig.Model model = llm.getProviders().get("test").getModels().get(0);
        model.setInput(Arrays.asList("text", "image"));
        return new ModelResolver(llm);
    }

    private static LlmGateway gateway(ModelResolver resolver, LlmTransport transport) {
        return new LlmGateway(resolver, Collections.singletonList(transport),
                new NoopLlmCallStore(), new DirectExecutorProvider(),
                new SequentialToolExecutor());
    }

    /**
     * 记录请求并按先入先出返回响应；异步路径同步完成，方便断言。
     */
    private static final class CapturingTransport implements LlmTransport {

        final List<LlmRequest> requests =
                Collections.synchronizedList(new ArrayList<LlmRequest>());

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            return ok();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(ok());
        }

        private static LlmCompletion ok() {
            return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }
}

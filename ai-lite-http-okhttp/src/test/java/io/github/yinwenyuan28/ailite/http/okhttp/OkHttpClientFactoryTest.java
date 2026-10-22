package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OkHttp 工厂：默认 Dispatcher 上限、显式配置和非法值。
 */
public class OkHttpClientFactoryTest {

    @Test
    public void usesOkHttpDefaultsWhenUnset() {
        OkHttpClient client = OkHttpClientFactory.create(new AiLiteConfig.Http());

        assertThat(client.dispatcher().getMaxRequests()).isEqualTo(64);
        assertThat(client.dispatcher().getMaxRequestsPerHost()).isEqualTo(5);
        assertThat(client.retryOnConnectionFailure()).isFalse();
    }

    @Test
    public void appliesDispatcherConfig() {
        AiLiteConfig.Http config = new AiLiteConfig.Http();
        config.getDispatcher().setMaxRequests(Integer.valueOf(12));
        config.getDispatcher().setMaxRequestsPerHost(Integer.valueOf(3));

        OkHttpClient client = OkHttpClientFactory.create(config);

        assertThat(client.dispatcher().getMaxRequests()).isEqualTo(12);
        assertThat(client.dispatcher().getMaxRequestsPerHost()).isEqualTo(3);
    }

    @Test
    public void rejectsNonPositiveLimits() {
        AiLiteConfig.Http config = new AiLiteConfig.Http();
        config.getDispatcher().setMaxRequests(Integer.valueOf(0));

        assertThatThrownBy(() -> OkHttpClientFactory.create(config))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CONFIG));
    }
}

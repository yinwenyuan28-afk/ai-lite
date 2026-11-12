package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.internal.http.Timeouts;
import org.junit.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时归一化：非正值一律取框架默认值，正值原样透传。
 */
public class TimeoutsTest {

    @Test
    public void positiveValuesArePassedThrough() {
        HttpRequest request = request(1500, 2500);
        assertThat(Timeouts.connect(request)).isEqualTo(1500);
        assertThat(Timeouts.read(request)).isEqualTo(2500);
    }

    @Test
    public void zeroFallsBackToDefault() {
        HttpRequest request = request(0, 0);
        assertThat(Timeouts.connect(request)).isEqualTo(Timeouts.DEFAULT_CONNECT_MS);
        assertThat(Timeouts.read(request)).isEqualTo(Timeouts.DEFAULT_READ_MS);
    }

    @Test
    public void negativeFallsBackToDefault() {
        HttpRequest request = request(-1, -1);
        assertThat(Timeouts.connect(request)).isEqualTo(Timeouts.DEFAULT_CONNECT_MS);
        assertThat(Timeouts.read(request)).isEqualTo(Timeouts.DEFAULT_READ_MS);
    }

    @Test
    public void defaultsMatchProviderDefaults() {
        // 与 AiLiteConfig.Provider 的默认值保持一致：10s 连接、120s 读取。
        assertThat(Timeouts.DEFAULT_CONNECT_MS).isEqualTo(10000);
        assertThat(Timeouts.DEFAULT_READ_MS).isEqualTo(120000);
    }

    private static HttpRequest request(int connect, int read) {
        return new HttpRequest("http://localhost/v1", Collections.<String, String>emptyMap(),
                "{}", connect, read, null);
    }
}

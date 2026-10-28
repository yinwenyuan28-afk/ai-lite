package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片值对象的归一化：MIME 去空白、data URL 前缀剥离。
 */
public class LlmImageTest {

    @Test
    public void stripsBase64DataUrlPrefix() {
        LlmImage image = new LlmImage("image/png",
                "data:image/png;base64,QUJD");

        assertThat(image.getData()).isEqualTo("QUJD");
        assertThat(image.getMime()).isEqualTo("image/png");
    }

    @Test
    public void stripsPrefixCaseInsensitivelyAndKeepsPayload() {
        LlmImage image = new LlmImage("image/jpeg",
                "DATA:image/jpeg;BASE64,AAAA");

        assertThat(image.getData()).isEqualTo("AAAA");
    }

    @Test
    public void keepsRawBase64Unchanged() {
        LlmImage image = new LlmImage("image/png", "QUJD");

        assertThat(image.getData()).isEqualTo("QUJD");
    }

    @Test
    public void trimsRawBase64Whitespace() {
        LlmImage image = new LlmImage("image/png", "  QUJD \n");

        assertThat(image.getData()).isEqualTo("QUJD");
    }

    @Test
    public void doesNotStripNonBase64DataUrl() {
        LlmImage image = new LlmImage("image/svg+xml",
                "data:image/svg+xml;charset=utf-8,<svg/>");

        // 构造器不误伤；网关会在 LlmTexts.validate 里拒绝非 base64 data URL。
        assertThat(image.getData()).isEqualTo("data:image/svg+xml;charset=utf-8,<svg/>");
    }

    @Test
    public void trimsMimeButKeepsNullForValidation() {
        assertThat(new LlmImage(" image/png ", "AAAA").getMime()).isEqualTo("image/png");
        assertThat(new LlmImage(null, "AAAA").getMime()).isNull();
        assertThat(new LlmImage("image/png", null).getData()).isNull();
    }
}

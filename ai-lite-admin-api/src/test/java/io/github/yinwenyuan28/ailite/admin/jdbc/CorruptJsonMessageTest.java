package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * JSON 列损坏时的报错内容。
 *
 * <p><b>回归背景</b>：原来把 {@code ex.getMessage()} 拼进异常消息，而 Jackson 的
 * 解析错误默认会带上出错位置的源文本片段。这些列存的是请求头与附加请求体，
 * 可能含自定义鉴权头，而管理接口本身没有鉴权——任何人构造一条坏 JSON 就能让
 * 响应体回显那段内容。</p>
 *
 * <p>要求：消息保留定位信息（表名、行标识、列名、是不是 JSON 问题），
 * 但不含列里的原文；原始异常仍作为 cause 保留，供日志查看。</p>
 */
public class CorruptJsonMessageTest {

    private JdbcTemplate jdbc;
    private JdbcRuntimeCatalog catalog;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("corrupt-json");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                "AI_LITE_BIZ_BINDING", 0);
    }

    /**
     * 模型级请求头里塞一段像密钥的原文，报错不能把它带出来。
     */
    @Test
    public void modelHeadersErrorDoesNotEchoColumnText() {
        catalog.insertModelIfAbsent("demo", "qwen");
        jdbc.update("UPDATE AI_LITE_MODEL SET MODEL_HEADERS = ? WHERE MODEL_ID = ?",
                "{\"Authorization\":\"Bearer sk-live-SHOULD-NOT-LEAK\", oops", "qwen");
        catalog.invalidate();

        String message = failureMessageOf("demo", "qwen");
        assertThat(message).contains("MODEL_HEADERS").contains("不是合法 JSON");
        assertThat(message).doesNotContain("SHOULD-NOT-LEAK")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer");
    }

    /**
     * 供应商级附加请求体同理，且消息要能定位到是哪家供应商。
     */
    @Test
    public void providerExtraBodyErrorDoesNotEchoColumnText() {
        catalog.saveOverlay(new ProviderOverlay("demo", "http://x/v1", "key",
                Integer.valueOf(1000), Integer.valueOf(2000)));
        jdbc.update("UPDATE AI_LITE_PROVIDER_OVERLAY SET PROVIDER_EXTRA_BODY = ?"
                + " WHERE PROVIDER_ID = ?",
                "{\"X-Api-Key\":\"SHOULD-NOT-LEAK\", bad", "demo");
        catalog.invalidate();

        String message;
        try {
            catalog.findProvider("demo");
            throw new AssertionError("损坏的 JSON 列必须报错");
        } catch (IllegalStateException ex) {
            message = String.valueOf(ex.getMessage());
        }
        assertThat(message).contains("PROVIDER_EXTRA_BODY").contains("demo")
                .contains("不是合法 JSON");
        assertThat(message).doesNotContain("SHOULD-NOT-LEAK").doesNotContain("X-Api-Key");
    }

    /**
     * 嵌套的档位映射列也要覆盖到，不能漏掉第三处。
     */
    @Test
    public void nestedMapErrorDoesNotEchoColumnText() {
        catalog.insertModelIfAbsent("demo", "qwen");
        jdbc.update("UPDATE AI_LITE_MODEL SET SAMPLER_PARAMS_BY_LEVEL = ? WHERE MODEL_ID = ?",
                "{\"high\":{\"api_key\":\"SHOULD-NOT-LEAK\"}", "qwen");
        catalog.invalidate();

        String message = failureMessageOf("demo", "qwen");
        assertThat(message).contains("SAMPLER_PARAMS_BY_LEVEL").contains("不是合法 JSON");
        assertThat(message).doesNotContain("SHOULD-NOT-LEAK").doesNotContain("api_key");
    }

    /**
     * 原始异常仍要作为 cause 保留，否则日志里也看不到真正原因了。
     */
    @Test
    public void originalExceptionIsKeptAsCause() {
        catalog.insertModelIfAbsent("demo", "qwen");
        jdbc.update("UPDATE AI_LITE_MODEL SET EXTRA_BODY = ? WHERE MODEL_ID = ?", "{bad", "qwen");
        catalog.invalidate();

        try {
            catalog.findModel("demo", "qwen");
            fail("损坏的 JSON 列必须报错，不能静默当空");
        } catch (IllegalStateException ex) {
            assertThat(ex.getCause()).isInstanceOf(
                    com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }

    /**
     * 读一次模型行，返回抛出的异常消息。
     *
     * @param providerId 供应商
     * @param modelId    模型
     * @return 异常消息
     */
    private String failureMessageOf(String providerId, String modelId) {
        try {
            catalog.findModel(providerId, modelId);
        } catch (IllegalStateException ex) {
            return String.valueOf(ex.getMessage());
        }
        throw new AssertionError("损坏的 JSON 列必须报错，实际没有");
    }
}

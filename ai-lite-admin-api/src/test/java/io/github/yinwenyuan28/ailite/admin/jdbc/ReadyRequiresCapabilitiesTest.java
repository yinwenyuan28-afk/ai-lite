package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启用前的参数完整性校验。
 *
 * <p><b>回归背景</b>：{@code insertModelIfAbsent} 建的行所有能力参数都是 NULL。
 * 早先可以直接对这一行调 {@code setReady(true)}，得到一个「已启用、参数全空」的模型：
 * 上下文窗口 0 让预算与自动压缩整条跳过，入参只剩 text 让图片被拒，
 * 而管理页显示 READY 为绿色——没有任何地方提示参数没填。</p>
 *
 * <p>现在把校验放在启用这个动作上：参数不全就拒绝，并列出缺哪几列。
 * 停用不做校验，已有模型要能随时下线。</p>
 */
public class ReadyRequiresCapabilitiesTest {

    private JdbcTemplate jdbc;
    private JdbcRuntimeCatalog catalog;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("ready-capabilities");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                "AI_LITE_BIZ_BINDING", 0);
    }

    /**
     * 参数全空的行不能被启用，且消息要列出缺哪些列。
     */
    @Test
    public void blankRowCannotBeEnabled() {
        catalog.insertModelIfAbsent("demo", "qwen");

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                catalog.setReady("demo", "qwen", true);
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("参数不完整")
                .hasMessageContaining("CONTEXT_WINDOW")
                .hasMessageContaining("INPUT_MODES");

        assertThat(catalog.findModel("demo", "qwen").isReady()).isFalse();
    }

    /**
     * 填完参数就能启用。
     */
    @Test
    public void filledRowCanBeEnabled() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");

        catalog.setReady("demo", "qwen", true);

        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
    }

    /**
     * 停用不做参数校验：参数被清掉的模型仍要能下线。
     */
    @Test
    public void disablingNeverRequiresCapabilities() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");
        catalog.setReady("demo", "qwen", true);
        jdbc.update("UPDATE AI_LITE_MODEL SET CONTEXT_WINDOW = NULL, MAX_TOKENS = NULL,"
                + " INPUT_MODES = NULL, REASONING = NULL, THINKING_FORMAT = NULL"
                + " WHERE MODEL_ID = 'qwen'");
        catalog.invalidate();

        catalog.setReady("demo", "qwen", false);

        assertThat(catalog.findModel("demo", "qwen").isReady()).isFalse();
    }

    /**
     * REASONING 为 NULL 也算缺项——它决定思考档会不会被压到 off，不能默认当 false。
     */
    @Test
    public void nullReasoningCountsAsMissing() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");
        jdbc.update("UPDATE AI_LITE_MODEL SET REASONING = NULL WHERE MODEL_ID = 'qwen'");
        catalog.invalidate();

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                catalog.setReady("demo", "qwen", true);
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REASONING");
    }

    /**
     * 目录行要能表达「REASONING 没填」，否则上面那条校验无从判断。
     */
    @Test
    public void nullReasoningIsRepresentable() {
        catalog.insertModelIfAbsent("demo", "qwen");

        CatalogModel model = catalog.findModel("demo", "qwen");

        assertThat(model.getReasoningFlag()).isNull();
        assertThat(model.isReasoning()).isFalse();
    }

    /**
     * REASONING 有值时按值解析，不能被当成没填。
     */
    @Test
    public void explicitReasoningIsPreserved() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");

        CatalogModel model = catalog.findModel("demo", "qwen");

        assertThat(model.getReasoningFlag()).isEqualTo(Boolean.TRUE);
        assertThat(model.isReasoning()).isTrue();
    }
}

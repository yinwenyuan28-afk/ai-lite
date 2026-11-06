package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录行的能力参数为空时，要回落到配置文件。
 *
 * <p><b>回归背景</b>：{@code toConfigModel} 早先只从数据库取前七项能力参数，数据库
 * 列为 NULL 时直接落成内置默认值，完全不看配置文件里同名模型。后果是
 * 「数据库里建了行、还没填参数就启用」的模型：上下文窗口变成 0，上下文预算与自动
 * 压缩整条跳过；入参类型变成 text，图片被拒；思考格式被写死成 reasoning-effort。
 * 全程不报错，页面上还显示已启用。</p>
 *
 * <p>正确规则：数据库没填 → 用配置文件；配置文件也没有 → 用内置默认。
 * 数据库填了值（含 0）就以数据库为准。</p>
 */
public class ModelResolverCapabilityFallbackTest {

    /**
     * 数据库没填上下文窗口，配置文件写了 131072：必须用配置文件的值，否则预算检查失效。
     */
    @Test
    public void nullContextWindowFallsBackToYaml() {
        ResolvedModel resolved = resolve(
                rowWith(null, null, Collections.<String>emptyList(), null),
                yaml("m", true, Arrays.asList("text"), Integer.valueOf(131072),
                        Integer.valueOf(4096), "chat-template"));

        assertThat(resolved.getContextWindow()).isEqualTo(131072);
    }

    /**
     * 数据库没填入参类型，配置文件写了 image：必须能收图片。
     */
    @Test
    public void emptyInputModesFallBackToYaml() {
        ResolvedModel resolved = resolve(
                rowWith(Integer.valueOf(32768), Integer.valueOf(4096),
                        Collections.<String>emptyList(), "reasoning-effort"),
                yaml("m", true, Arrays.asList("text", "image"), Integer.valueOf(131072),
                        Integer.valueOf(4096), "reasoning-effort"));

        assertThat(resolved.isAcceptsImage()).isTrue();
    }

    /**
     * 数据库没填思考格式，配置文件写了 chat-template：请求体要按 chat-template 拼。
     */
    @Test
    public void blankThinkingFormatFallsBackToYaml() {
        ResolvedModel resolved = resolve(
                rowWith(Integer.valueOf(32768), Integer.valueOf(4096),
                        Collections.singletonList("text"), "  "),
                yaml("m", true, Collections.singletonList("text"), Integer.valueOf(131072),
                        Integer.valueOf(4096), "chat-template"));

        assertThat(resolved.getThinkingFormat()).isEqualTo("chat-template");
    }

    /**
     * 数据库没填推理开关，配置文件说支持推理：思考档不能被压到 off。
     */
    @Test
    public void nullReasoningFallsBackToYaml() {
        ResolvedModel resolved = resolveWithNullReasoning(
                rowWith(Integer.valueOf(32768), Integer.valueOf(4096),
                        Collections.singletonList("text"), "reasoning-effort"),
                yaml("m", true, Collections.singletonList("text"), Integer.valueOf(131072),
                        Integer.valueOf(4096), "reasoning-effort"));

        assertThat(resolved.isReasoning()).isTrue();
    }

    /**
     * 配置文件里没有这个模型时，仍用内置默认值，不能因为回落逻辑变成 null。
     */
    @Test
    public void missingYamlModelStillUsesBuiltInDefaults() {
        ResolvedModel resolved = resolve(
                rowWith(null, null, Collections.<String>emptyList(), null), null);

        assertThat(resolved.getContextWindow()).isEqualTo(0);
        assertThat(resolved.getMaxTokens()).isEqualTo(8192);
        assertThat(resolved.getThinkingFormat()).isEqualTo("reasoning-effort");
        assertThat(resolved.isAcceptsImage()).isFalse();
    }

    /**
     * 数据库填了值就以数据库为准，配置文件不能盖掉它。
     */
    @Test
    public void databaseValueWinsOverYaml() {
        ResolvedModel resolved = resolve(
                rowWith(Integer.valueOf(8000), Integer.valueOf(512),
                        Collections.singletonList("text"), "none"),
                yaml("m", true, Arrays.asList("text", "image"), Integer.valueOf(131072),
                        Integer.valueOf(4096), "chat-template"));

        assertThat(resolved.getContextWindow()).isEqualTo(8000);
        assertThat(resolved.getMaxTokens()).isEqualTo(512);
        assertThat(resolved.getThinkingFormat()).isEqualTo("none");
        assertThat(resolved.isAcceptsImage()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param contextWindow 上下文窗口；null 表示数据库没填
     * @param maxTokens     输出上限；null 表示数据库没填
     * @param inputModes    入参类型；空表示数据库没填
     * @param thinkingFormat 思考格式；空白表示数据库没填
     * @return 已启用的目录行
     */
    private static CatalogModel rowWith(Integer contextWindow, Integer maxTokens,
                                        java.util.List<String> inputModes, String thinkingFormat) {
        return new CatalogModel("demo", "m", true, false, inputModes, contextWindow, maxTokens,
                thinkingFormat, "m", 1L);
    }

    /**
     * 推理开关为「没填」的目录行。老构造器的 boolean 表达不了这个状态，
     * 所以走目录行专用工厂方法。
     *
     * @return 已启用、推理开关为 null 的目录行
     */
    private static CatalogModel rowWithNullReasoningHere() {
        return CatalogModel.fromCatalogRow("demo", "m", true, null,
                Collections.singletonList("text"), Integer.valueOf(32768), Integer.valueOf(4096),
                "reasoning-effort", "m", 1L, null, null, null, null, null);
    }

    /**
     * 用给定目录行解析一次业务调用。
     *
     * @param row  目录行
     * @param yaml 配置文件里的同名模型；null 表示没有
     * @return 解析结果
     */
    private static ResolvedModel resolve(final CatalogModel row, AiLiteConfig.Model yaml) {
        return new ModelResolver(llm(yaml), 60_000L, catalog(row)).resolveBiz("demo-chat");
    }

    /**
     * 推理开关为 null 的那条用例专用。
     *
     * @param ignored 未使用的目录行（保留签名便于阅读）
     * @param yaml    配置文件里的同名模型
     * @return 解析结果
     */
    private static ResolvedModel resolveWithNullReasoning(CatalogModel ignored, AiLiteConfig.Model yaml) {
        return new ModelResolver(llm(yaml), 60_000L, catalog(rowWithNullReasoningHere()))
                .resolveBiz("demo-chat");
    }

    /**
     * @param model 配置文件里的模型；null 表示配置文件里没有这个模型
     * @return 带一家供应商的配置
     */
    private static AiLiteConfig.Llm llm(AiLiteConfig.Model model) {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        provider.setApiKey("test-key");
        provider.setModels(new ArrayList<AiLiteConfig.Model>());
        if (model != null) {
            provider.getModels().add(model);
        }
        llm.getProviders().put("demo", provider);
        return llm;
    }

    /**
     * @param id            模型 id
     * @param reasoning     是否支持推理
     * @param input         入参类型
     * @param contextWindow 上下文窗口
     * @param maxTokens     输出上限
     * @param thinkingFormat 思考格式
     * @return 配置文件里的模型
     */
    private static AiLiteConfig.Model yaml(String id, boolean reasoning, java.util.List<String> input,
                                          Integer contextWindow, Integer maxTokens,
                                          String thinkingFormat) {
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId(id);
        model.setReasoning(reasoning);
        model.setInput(input);
        model.setContextWindow(contextWindow);
        model.setMaxTokens(maxTokens);
        model.setThinkingFormat(thinkingFormat);
        return model;
    }

    /**
     * @param row 目录行
     * @return 只认这一行的目录
     */
    private static RuntimeCatalog catalog(final CatalogModel row) {
        return new RuntimeCatalog() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public ProviderOverlay findProvider(String providerId) {
                return null;
            }

            @Override
            public CatalogModel findModel(String providerId, String modelId) {
                return row;
            }

            @Override
            public BizBinding findBinding(String bizId) {
                return new BizBinding("demo-chat", "demo", "m", "off");
            }

            @Override
            public void invalidate() {
            }
        };
    }
}

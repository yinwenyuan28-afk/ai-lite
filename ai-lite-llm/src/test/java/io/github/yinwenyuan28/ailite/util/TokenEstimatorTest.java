package io.github.yinwenyuan28.ailite.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 加权 token 估算器：准确度门槛与字符类别的权重差异。
 *
 * <p>准确度断言用 ±35% 的硬阈值。这不是精确性要求，而是"不能低估到失效"的下限：
 * 早期用 {@code 字符数 / 2.5} 对中文低估 40%~60%，会直接导致压缩判断失效。</p>
 */
public class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    public void nullAndEmptyAreZero() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
    }

    /**
     * 纯中文 1000 字：主流中文分词器约 700~1100 token，估算应落在合理带内。
     */
    @Test
    public void pureChineseIsNotUnderestimated() {
        String text = repeat("中", 1000);
        int estimated = estimator.estimate(text);

        // 权重 0.6 → 约 600（浮点累加后向上取整可能多 1）。
        assertThat(estimated).isBetween(600, 602);
        assertThat((double) estimated).isGreaterThan(700D * 0.65D);
        assertThat((double) estimated).isLessThan(1000D * 1.35D);
    }

    /**
     * 纯英文 4000 字符：约 1000 token。权重 0.25 → 1000，几乎精确。
     */
    @Test
    public void pureAsciiIsAccurate() {
        String text = repeat("a", 4000);
        int estimated = estimator.estimate(text);

        assertThat(estimated).isEqualTo(1000);
        assertThat((double) estimated).isGreaterThan(1000D * 0.65D);
        assertThat((double) estimated).isLessThan(1000D * 1.35D);
    }

    /**
     * 混合文本：中英文各半，估算应落在两者之间的合理范围。
     */
    @Test
    public void mixedTextLandsBetween() {
        String chinese = repeat("中", 500);
        String ascii = repeat("a", 2000);
        int chineseOnly = estimator.estimate(chinese);
        int asciiOnly = estimator.estimate(ascii);
        int mixed = estimator.estimate(chinese + ascii);

        assertThat(mixed).isEqualTo(chineseOnly + asciiOnly);
        assertThat(mixed).isGreaterThan(asciiOnly);
    }

    /**
     * 中文文档估算必须显著高于“字符数 / 2.5”的旧公式。
     * 这条是防止有人把估算器改回简单除法的回归断言。
     */
    @Test
    public void chineseEstimateBeatsNaiveCharDivision() {
        String chinese = repeat("中", 1000);
        int estimated = estimator.estimate(chinese);
        int naive = 1000 / 2;

        assertThat(estimated).as("中文估算不能低于朴素除法").isGreaterThan(naive);
    }

    @Test
    public void messageOverheadIsAdded() {
        assertThat(estimator.estimateMessage("hi"))
                .isEqualTo(estimator.estimate("hi") + 4);
        assertThat(estimator.estimateMessage(null)).isEqualTo(4);
    }

    @Test
    public void cjkPunctuationCountsAsCjk() {
        // 全角标点属于 CJK 符号区，按中文权重算，避免"全是标点却低估"。
        assertThat(estimator.estimate("，，，")).isGreaterThan(estimator.estimate(",,,"));
    }

    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }
}

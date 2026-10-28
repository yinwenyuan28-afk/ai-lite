package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考档钳制规则。
 */
public class LlmThinkingTest {

    @Test
    public void nonReasoningModelAlwaysGetsOff() {
        assertThat(LlmThinking.clamp(LlmThinkingLevel.HIGH, false, null))
                .isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void offStaysOff() {
        assertThat(LlmThinking.clamp(LlmThinkingLevel.OFF, true, null))
                .isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void withoutMapXhighAndMaxFallBackToHigh() {
        Map<String, String> empty = Collections.emptyMap();

        assertThat(LlmThinking.clamp(LlmThinkingLevel.XHIGH, true, empty))
                .isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(LlmThinking.clamp(LlmThinkingLevel.MAX, true, empty))
                .isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(LlmThinking.clamp(LlmThinkingLevel.MEDIUM, true, empty))
                .isEqualTo(LlmThinkingLevel.MEDIUM);
    }

    @Test
    public void unsupportedLevelFallsBackToNearestSupportedBelow() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("off", "off");
        map.put("low", "low");
        map.put("high", "high");

        assertThat(LlmThinking.clamp(LlmThinkingLevel.MAX, true, map))
                .isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(LlmThinking.clamp(LlmThinkingLevel.MEDIUM, true, map))
                .isEqualTo(LlmThinkingLevel.LOW);
    }

    @Test
    public void nullMappingMeansUnsupported() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("minimal", null);
        map.put("low", "low");

        assertThat(LlmThinking.clamp(LlmThinkingLevel.MINIMAL, true, map))
                .isEqualTo(LlmThinkingLevel.OFF);
    }

    @Test
    public void thinkingEnabledOnlyForNonOffLevels() {
        assertThat(LlmThinking.thinkingEnabled(null)).isFalse();
        assertThat(LlmThinking.thinkingEnabled(LlmThinkingLevel.OFF)).isFalse();
        assertThat(LlmThinking.thinkingEnabled(LlmThinkingLevel.LOW)).isTrue();
    }

    @Test
    public void parseAcceptsKnownWireNamesCaseInsensitively() {
        assertThat(LlmThinkingLevel.parse(" HIGH ")).isEqualTo(LlmThinkingLevel.HIGH);
    }

    @Test
    public void parseToleratesYamlBooleanTrap() {
        // YAML 会把不带引号的 off/on 解析成布尔值。
        assertThat(LlmThinkingLevel.parse("false")).isEqualTo(LlmThinkingLevel.OFF);
        assertThat(LlmThinkingLevel.parse("no")).isEqualTo(LlmThinkingLevel.OFF);
        assertThat(LlmThinkingLevel.parse("true")).isEqualTo(LlmThinkingLevel.HIGH);
        assertThat(LlmThinkingLevel.parse("yes")).isEqualTo(LlmThinkingLevel.HIGH);
    }
}

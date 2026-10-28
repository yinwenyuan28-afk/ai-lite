package io.github.yinwenyuan28.ailite.json;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson 门面的读写行为。
 */
public class AiLiteJsonTest {

    @Test
    public void mapRoundTrips() {
        Map<String, Object> source = new HashMap<String, Object>();
        source.put("name", "now");
        source.put("count", Integer.valueOf(2));

        String json = AiLiteJson.toJson(source);
        Map<String, Object> parsed = AiLiteJson.readMap(json);

        assertThat(parsed).containsEntry("name", "now");
        assertThat(parsed).containsEntry("count", Integer.valueOf(2));
    }

    @Test
    public void unknownFieldsAreIgnored() {
        Sample sample = AiLiteJson.read("{\"name\":\"a\",\"unknown\":1}", Sample.class);

        assertThat(sample.name).isEqualTo("a");
    }

    @Test
    public void invalidJsonFailsFast() {
        assertThatThrownBy(() -> AiLiteJson.readMap("{"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON 无法解析");
    }

    @Test
    public void nullIsSerializedAsNull() {
        assertThat(AiLiteJson.toJson(null)).isEqualTo("null");
    }

    /** 测试用简单 Bean。 */
    public static class Sample {
        public String name;
    }
}

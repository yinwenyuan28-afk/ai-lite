package io.github.yinwenyuan28.ailite.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.Map;

/**
 * Jackson 门面。核心模块内部只通过这里做 JSON 读写，公开 API 不暴露任何 JSON 库类型。
 *
 * <p>设计意图：把 Jackson 的配置和版本差异收在一个类里。以后要换 JSON 实现，
 * 只需要改这个类，不会波及传输层、会话编解码和工具参数。</p>
 */
public final class AiLiteJson {

    private static final ObjectMapper MAPPER = createMapper();

    private AiLiteJson() {
    }

    /**
     * 返回共享的 ObjectMapper。调用方不应修改它的配置。
     *
     * @return 配置好的 Jackson 实例
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 序列化为 JSON 文本。
     *
     * @param value 任意可序列化对象
     * @return JSON 文本
     * @throws IllegalStateException 序列化失败时抛出
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    /**
     * 解析为 JSON 树。
     *
     * @param json JSON 文本
     * @return 根节点
     * @throws IllegalArgumentException 文本不是合法 JSON 时抛出
     */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException ex) {
            throw new IllegalArgumentException("JSON 无法解析", ex);
        }
    }

    /**
     * 解析为指定类型。
     *
     * @param json JSON 文本
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 反序列化结果
     * @throws IllegalArgumentException 文本不是合法 JSON 或结构不匹配时抛出
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException ex) {
            throw new IllegalArgumentException("JSON 无法解析", ex);
        }
    }

    /**
     * 解析为字符串键的 Map，供工具参数和通用请求体使用。
     *
     * @param json JSON 文本
     * @return 参数 Map
     * @throws IllegalArgumentException 文本不是合法 JSON 对象时抛出
     */
    public static Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("JSON 无法解析", ex);
        }
    }

    /**
     * 把任意对象转换为字符串键的 Map。
     *
     * @param value 任意对象
     * @return 参数 Map
     */
    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 旧数据或新版本字段不应导致会话读取失败。
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 会话里的 updatedAt 明确用毫秒时间戳，不输出 ISO 字符串。
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        return mapper;
    }
}

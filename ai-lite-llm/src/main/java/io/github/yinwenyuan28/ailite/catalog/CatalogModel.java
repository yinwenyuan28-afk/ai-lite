package io.github.yinwenyuan28.ailite.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录中的一个模型。能力参数在未就绪时可以为空，这时不能被业务选中。
 *
 * <p>「就绪」是一个人工开关（{@code READY} 列），与能力参数是否填好无关：
 * 参数可能填了但还没验证，也可能压根没填。只有显式的启用动作会改变它，
 * 保存能力参数不影响该状态。</p>
 *
 * <p>后五个字段（思考档映射、采样参数、分档采样参数、模型请求头、模型附加请求体）
 * 是请求的固有属性，不是运营状态。它们为 {@code null} 表示数据库没配，
 * 解析时应从配置文件里的同名模型继承；空 Map 表示明确清空。</p>
 */
public final class CatalogModel {

    private final String providerId;
    private final String modelId;
    private final boolean ready;
    private final boolean reasoning;
    private final List<String> inputModes;
    private final Integer contextWindow;
    private final Integer maxTokens;
    private final String thinkingFormat;
    private final String displayName;
    private final long updatedAtMillis;
    private final Map<String, String> thinkingLevelMap;
    private final Map<String, Object> samplerParams;
    private final Map<String, Map<String, Object>> samplerParamsByLevel;
    private final Map<String, String> modelHeaders;
    private final Map<String, Object> extraBody;

    /**
     * 不带模型级请求参数的构造器，保留给既有调用方。
     *
     * <p>五个请求参数字段为 {@code null}，含义是「数据库没配，沿用配置文件」。</p>
     *
     * @param providerId      供应商 id
     * @param modelId         模型 id
     * @param ready           是否已启用（人工开关，与能力参数是否填好无关）
     * @param reasoning       是否支持推理
     * @param inputModes      入参类型，可空
     * @param contextWindow   上下文窗口，可空
     * @param maxTokens       输出上限，可空
     * @param thinkingFormat  思考格式，可空
     * @param displayName     显示名，可空
     * @param updatedAtMillis 更新时间，毫秒；同一对键多行时取较大值
     */
    public CatalogModel(String providerId, String modelId, boolean ready, boolean reasoning,
                        List<String> inputModes, Integer contextWindow, Integer maxTokens,
                        String thinkingFormat, String displayName, long updatedAtMillis) {
        this(providerId, modelId, ready, reasoning, inputModes, contextWindow, maxTokens,
                thinkingFormat, displayName, updatedAtMillis, null, null, null, null, null);
    }

    /**
     * @param providerId           供应商 id
     * @param modelId              模型 id
     * @param ready                是否已启用（人工开关，与能力参数是否填好无关）
     * @param reasoning            是否支持推理
     * @param inputModes           入参类型，可空
     * @param contextWindow        上下文窗口，可空
     * @param maxTokens            输出上限，可空
     * @param thinkingFormat       思考格式，可空
     * @param displayName          显示名，可空
     * @param updatedAtMillis      更新时间，毫秒；同一对键多行时取较大值
     * @param thinkingLevelMap     思考档映射；null 表示沿用配置文件
     * @param samplerParams        默认采样参数；null 表示沿用配置文件
     * @param samplerParamsByLevel 按思考档覆盖的采样参数；null 表示沿用配置文件
     * @param modelHeaders         模型级请求头；null 表示沿用配置文件
     * @param extraBody            模型级附加请求体；null 表示沿用配置文件
     */
    public CatalogModel(String providerId, String modelId, boolean ready, boolean reasoning,
                        List<String> inputModes, Integer contextWindow, Integer maxTokens,
                        String thinkingFormat, String displayName, long updatedAtMillis,
                        Map<String, String> thinkingLevelMap,
                        Map<String, Object> samplerParams,
                        Map<String, Map<String, Object>> samplerParamsByLevel,
                        Map<String, String> modelHeaders,
                        Map<String, Object> extraBody) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.ready = ready;
        this.reasoning = reasoning;
        this.inputModes = inputModes == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(inputModes));
        this.contextWindow = contextWindow;
        this.maxTokens = maxTokens;
        this.thinkingFormat = thinkingFormat;
        this.displayName = displayName;
        this.updatedAtMillis = updatedAtMillis;
        // 保持 null 与空 Map 的区别：null 是「没配」，空 Map 是「配了空」。
        this.thinkingLevelMap = thinkingLevelMap == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(thinkingLevelMap));
        this.samplerParams = samplerParams == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(samplerParams));
        this.samplerParamsByLevel = copyNested(samplerParamsByLevel);
        this.modelHeaders = modelHeaders == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(modelHeaders));
        this.extraBody = extraBody == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(extraBody));
    }

    /**
     * 复制嵌套 Map。null 表示「没配」，必须原样保留。
     *
     * @param source 原始嵌套映射
     * @return 只读副本；source 为 null 时返回 null
     */
    private static Map<String, Map<String, Object>> copyNested(
            Map<String, Map<String, Object>> source) {
        if (source == null) {
            return null;
        }
        Map<String, Map<String, Object>> copy = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            Map<String, Object> value = entry.getValue();
            copy.put(entry.getKey(), value == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value)));
        }
        return Collections.unmodifiableMap(copy);
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isReasoning() {
        return reasoning;
    }

    public List<String> getInputModes() {
        return inputModes;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public String getThinkingFormat() {
        return thinkingFormat;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    /**
     * @return 思考档映射；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, String> getThinkingLevelMap() {
        return thinkingLevelMap;
    }

    /**
     * @return 默认采样参数；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, Object> getSamplerParams() {
        return samplerParams;
    }

    /**
     * @return 按思考档覆盖的采样参数；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, Map<String, Object>> getSamplerParamsByLevel() {
        return samplerParamsByLevel;
    }

    /**
     * @return 模型级请求头；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, String> getModelHeaders() {
        return modelHeaders;
    }

    /**
     * @return 模型级附加请求体；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, Object> getExtraBody() {
        return extraBody;
    }
}

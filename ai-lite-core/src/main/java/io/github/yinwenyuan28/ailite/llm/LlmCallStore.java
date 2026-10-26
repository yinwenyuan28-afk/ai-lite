package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用记录存储 SPI。默认实现只打日志，不落库。
 *
 * <p><b>演化承诺（B 组：方法集终态）</b>：只有"写入一行"这一个职责，1.0 后
 * 不再新增方法。需要批量写入、查询、清理等能力时应新增独立接口（如
 * {@code LlmCallStoreV2}），保持老接口对所有既有实现零影响。</p>
 */
public interface LlmCallStore {

    /**
     * 写入一行记录。实现必须自行处理并发，且不能影响调用结果。
     *
     * @param record 调用记录
     */
    void insert(LlmCallRecord record);
}

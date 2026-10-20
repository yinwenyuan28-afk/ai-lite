package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用记录存储 SPI。默认实现只打日志，不落库。
 */
public interface LlmCallStore {

    /**
     * 写入一行记录。实现必须自行处理并发，且不能影响调用结果。
     *
     * @param record 调用记录
     */
    void insert(LlmCallRecord record);
}

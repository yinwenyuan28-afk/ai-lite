package io.github.yinwenyuan28.ailite.llm;

/**
 * 默认的空存储。调用记录只出现在日志里。
 */
public final class NoopLlmCallStore implements LlmCallStore {

    @Override
    public void insert(LlmCallRecord record) {
        // 默认不持久化。
    }
}

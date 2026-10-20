package io.github.yinwenyuan28.ailite.http;

import java.io.IOException;

/**
 * 流式响应的逐行回调。SSE 的每一行原样交给协议适配器解析。
 */
public interface StreamListener {

    /**
     * @param line 一行文本，不含换行符
     * @throws IOException 处理失败时抛出
     */
    void onLine(String line) throws IOException;
}

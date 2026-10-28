package io.github.yinwenyuan28.ailite.http;

/**
 * 诊断用的原始 HTTP 执行器：与 {@link HttpExecutor} 共用同一套连接池、TLS、
 * 超时与传输实现，但<b>不因状态码非 2xx 抛异常</b>，而是把状态码和响应正文原样
 * 交回调用方。
 *
 * <p><b>为什么要单独一个接口</b>：{@link HttpExecutor} 的契约是"非 2xx 即失败"，
 * 并为了控制错误消息体积把正文截断到几百字符。管理台"试跑"需要看到供应商返回的
 * 完整错误正文（401 的 message、400 的字段校验、网关的 HTML 错误页），截断后就
 * 失去诊断价值。按 SPI 的演化承诺（见 {@link HttpExecutor} 类注释），新能力走
 * 新接口，不往老接口加方法。</p>
 *
 * <p>实现必须保证：走的是<b>同一条</b>出站链路。如果试跑绕开配置的传输另起一个
 * 客户端，就会重演"管理台显示成功、业务调用全挂"的错位——这正是本接口要消除的
 * 问题。</p>
 *
 * <p>该接口只提供同步入口：试跑是人工触发的低频诊断动作，不值得为它引入异步
 * 取消链路。</p>
 */
public interface RawHttpExecutor extends HttpExecutor {

    /**
     * 执行一次请求，非 2xx 也正常返回。
     *
     * <p>只有网络层失败（连不上、超时、TLS 握手失败、取消）才抛
     * {@link io.github.yinwenyuan28.ailite.llm.LlmCallException}；HTTP 语义上的
     * 失败（4xx/5xx）以 {@link HttpResult} 返回，由调用方决定如何呈现。</p>
     *
     * @param request 请求。与 {@link HttpExecutor#execute} 使用同一模型，方法可为
     *                POST（试跑、同步）或 GET（探测 {@code /models}）
     * @return 状态码与完整响应体（3xx 同样原样返回，不做重定向跟随）
     * @throws io.github.yinwenyuan28.ailite.llm.LlmCallException 网络层失败、超时或取消
     */
    HttpResult executeRaw(HttpRequest request);
}

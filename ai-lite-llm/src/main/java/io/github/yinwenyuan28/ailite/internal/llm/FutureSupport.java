package io.github.yinwenyuan28.ailite.internal.llm;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 异步回调的统一收敛原语。
 *
 * <p>{@code whenComplete} 只是观察者：回调体抛出的异常会被收进它返回的那个
 * dependent stage 里，既不会冒出来，也不会让原来的 future 失败。当"通知调用方"
 * 只是回调体里的一行手工调用时，这行没跑到就等于 future 永久挂起（审查 F-01）。</p>
 *
 * <p>本原语把「任何异常都必须翻译成一次终态收敛」只实现一遍：回调体整体包
 * {@code catch (Throwable)}，异常交给 converge 抢占终态。converge 本身也兜底，
 * 保证原语自己不再抛异常。</p>
 */
public final class FutureSupport {

    private static final Logger log = LoggerFactory.getLogger(FutureSupport.class);

    private FutureSupport() {
    }

    /**
     * 注册一个受保护的完成观察者。
     *
     * @param source   被观察的 future
     * @param body     业务回调（原有逻辑一行不动）
     * @param converge 回调体抛异常时的终态收敛；必须直接抢占终态，不能再排队
     */
    public static <T> void safeWhenComplete(CompletableFuture<T> source,
                                            BiConsumer<T, Throwable> body,
                                            Consumer<LlmCallException> converge) {
        source.whenComplete((value, error) -> {
            try {
                body.accept(value, error);
            } catch (Throwable failure) {
                log.warn("异步回调异常，收敛为失败终态: {}", failure.toString());
                try {
                    converge.accept(wrap(failure));
                } catch (Throwable fatal) {
                    // 收敛路径本身也不能把异常丢进无人读取的 dependent stage。
                    log.warn("异步失败收敛再次失败: {}", fatal.toString());
                }
            }
        });
    }

    /**
     * 把任意 Throwable 包装成网关语义的异常，保留原 cause 与原始消息，
     * 便于调用方从 future 的异常里直接看到出错的扩展点。
     */
    public static LlmCallException wrap(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof LlmCallException) {
            return (LlmCallException) cause;
        }
        String message = cause == null ? "null" : String.valueOf(cause.getMessage());
        return new LlmCallException("异步回调异常: " + message, cause);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

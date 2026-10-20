package io.github.yinwenyuan28.ailite.assembly;

/**
 * 几块积木拼完之后对外跑的那一个对象。输入输出由实现类定义。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface Assembly<I, O> {

    /**
     * @param input 业务输入
     * @return 业务输出
     */
    O run(I input);
}

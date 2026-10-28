package io.github.yinwenyuan28.ailite.assembly;

/**
 * 用实现类写的一条 Workflow。方法里只使用传入的 Workflow。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface WorkflowSpec<I, O> {

    /**
     * @param workflow 单次调用和简单工具循环的组合
     * @param input    业务输入
     * @return 业务输出
     */
    O run(Workflow workflow, I input);
}

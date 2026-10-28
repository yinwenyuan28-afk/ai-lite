package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用记录要不要带上模型输入和输出。默认不记。
 *
 * <p>none 不记。failure 只记失败调用。all 每次都记。密钥不会写进正文。</p>
 */
public interface CallBodyPolicy {

    String NONE = "none";
    String FAILURE = "failure";
    String ALL = "all";

    /**
     * @return none、failure 或 all
     */
    String level();

    /**
     * @param level   当前档位
     * @param success 1 成功，0 失败
     * @return 这一行要不要写输入输出
     */
    static boolean store(String level, int success) {
        if (ALL.equals(level)) {
            return true;
        }
        return FAILURE.equals(level) && success == 0;
    }
}

package io.github.yinwenyuan28.ailite.util;

/**
 * 按字符类型加权的 token 估算器。
 *
 * <p><b>为什么不引 tokenizer</b>：JDK 8 生态没有可靠的通用 BPE 实现，引入会让依赖面
 * 变宽，而且各供应商的分词表不同，用 OpenAI 的 BPE 估国产模型反而更不准。
 * 目标是"够用来判断该不该压缩"，不是精确计费——精确值以供应商返回的 usage 为准。</p>
 *
 * <p><b>为什么不能简单用 {@code 字符数 / 2.5}</b>：该公式对英文大致成立，
 * 但中文字符通常占 1 个 token 左右，除以 2.5 会低估 40%~60%。这是一个中文为主的库，
 * 低估的后果是"以为没超，其实超了"，供应商直接报错。因此按字符类别加权：</p>
 *
 * <table border="1">
 *   <caption>权重取值</caption>
 *   <tr><th>字符类别</th><th>估算 token/字符</th><th>依据</th></tr>
 *   <tr><td>ASCII 与拉丁字母</td><td>0.25</td><td>英文约 4 字符/token</td></tr>
 *   <tr><td>中日韩文字</td><td>0.6</td><td>常用汉字接近 1 字/token，取偏保守值</td></tr>
 *   <tr><td>其余（标点、emoji、其他文字）</td><td>0.5</td><td>折中</td></tr>
 * </table>
 *
 * <p>每条消息另加固定开销 {@link #MESSAGE_OVERHEAD_TOKENS}，用于近似角色标记与分隔符。</p>
 */
public final class TokenEstimator {

    /** ASCII 字符权重。 */
    static final double ASCII_WEIGHT = 0.25D;

    /** 中日韩字符权重。 */
    static final double CJK_WEIGHT = 0.6D;

    /** 其他字符权重。 */
    static final double OTHER_WEIGHT = 0.5D;

    /** 单条消息的固定结构开销（角色标记、分隔符等）。 */
    static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 null
     * @return 估算 token 数，至少为 0
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0D;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            tokens += weightOf(text.charAt(i));
        }
        return (int) Math.ceil(tokens);
    }

    /**
     * 估算一条消息的 token 数，含固定结构开销。
     *
     * @param text 消息正文，可为 null
     * @return 估算 token 数
     */
    public int estimateMessage(String text) {
        return estimate(text) + MESSAGE_OVERHEAD_TOKENS;
    }

    /**
     * 单字符权重。
     *
     * @param c 字符
     * @return token 权重
     */
    static double weightOf(char c) {
        if (c < 0x80) {
            return ASCII_WEIGHT;
        }
        if (isCjk(c)) {
            return CJK_WEIGHT;
        }
        return OTHER_WEIGHT;
    }

    /**
     * 判断是否中日韩文字（含扩展区与兼容区）。
     *
     * <p>代理对（emoji 等）在这里按"其他"处理：{@code char} 层面无法区分，
     * 而 emoji 在多数分词器里约占 1~2 token，取 0.5 会低估，但这类内容占比很低，
     * 且压缩阈值本身留有余量，可接受。</p>
     *
     * @param c 字符
     * @return 是否中日韩文字
     */
    static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}

package io.github.yinwenyuan28.ailite.prompt;

/**
 * 存储层的表名自述。
 *
 * <p>用于装配期建表探测：目录模式要求提示词发布落库，启动时要确认表已建好。
 * 表名可被配置改掉（见 {@code ai-lite.prompt.jdbc.*-table}），探测必须问存储
 * 自己用的是什么表名，不能拿默认表名去猜——猜错的后果是"用户明明建了表，
 * 应用却说表不存在"，而且错误消息还指着默认表名。</p>
 *
 * <p>独立成接口而不是加到 {@link PromptStore}：后者是已有实现类（含用户自定义
 * 实现）的契约，往里面加方法会让现有实现类编译不过。表名属于"知道就更好"的
 * 可选能力，取不到时调用方按默认表名兜底即可。</p>
 */
public interface PromptTableNames {

    /** @return 版本与模板正文表名 */
    String getVersionTable();

    /** @return 每个 key 的生效指针表名 */
    String getActiveTable();

    /** @return 操作审计表名 */
    String getAuditTable();
}

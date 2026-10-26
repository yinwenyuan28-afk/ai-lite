package io.github.yinwenyuan28.ailite.prompt;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 提示词存储 SPI。
 *
 * <p><b>演化承诺（A 组：允许生长）</b>：本接口是业务扩展点，未来确定会新增能力
 * （如按操作人查版本、按时间范围查审计、批量归档）。届时<b>只允许以 default 方法
 * 新增</b>，默认实现应抛 {@link UnsupportedOperationException} 或委托旧方法，
 * 保证已有实现类无需修改即可编译运行。不得新增抽象方法，不得修改或删除现有方法。</p>
 *
 * <p>实现方需要保证 {@link #activate} 的原子性：退役旧版本、发布目标版本、
 * 更新生效指针三步要么全成功，要么全失败。</p>
 *
 * <p><b>不包含环境隔离</b>：一个 key 只有一个全局生效版本，不支持 dev/staging/prod
 * 各自生效。{@code activate} 的"退役其他已发布版本"语义即终态。</p>
 */
public interface PromptStore {

    /**
     * @param promptKey 提示词 key
     * @return 当前生效版本；没有生效版本时为空
     */
    Optional<PromptVersion> findActive(String promptKey);

    /**
     * @param versionId 版本 id
     * @return 版本；不存在时为空
     */
    Optional<PromptVersion> findVersion(String versionId);

    /**
     * @param promptKey 提示词 key
     * @param limit     最大条数，按版本号倒序
     * @return 版本列表
     */
    List<PromptVersion> versions(String promptKey, int limit);

    /**
     * @param promptKey 提示词 key
     * @param limit     最大条数，按创建时间正序
     * @return 审计列表
     */
    List<PromptAudit> audits(String promptKey, int limit);

    /**
     * @param promptKey 提示词 key
     * @return 下一个版本号；没有历史版本时返回 1
     */
    int nextVersionNo(String promptKey);

    /**
     * 插入或按 versionId 更新一个版本。
     *
     * @param version 版本
     */
    void saveVersion(PromptVersion version);

    /**
     * 原子切换生效版本：退役同 key 下其他 published 版本，把目标版本置为 published，
     * 并更新生效指针和 revision。
     *
     * @param promptKey   提示词 key
     * @param versionId   目标版本 id
     * @param actor       操作人
     * @param publishTime 发布时间
     */
    void activate(String promptKey, String versionId, String actor, Date publishTime);

    /**
     * @param audit 审计记录
     */
    void audit(PromptAudit audit);
}

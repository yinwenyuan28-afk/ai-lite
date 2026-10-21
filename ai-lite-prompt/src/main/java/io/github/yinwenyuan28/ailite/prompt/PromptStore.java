package io.github.yinwenyuan28.ailite.prompt;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 提示词存储 SPI。
 *
 * <p>实现方需要保证 {@link #activate} 的原子性：退役旧版本、发布目标版本、
 * 更新生效指针三步要么全成功，要么全失败。</p>
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

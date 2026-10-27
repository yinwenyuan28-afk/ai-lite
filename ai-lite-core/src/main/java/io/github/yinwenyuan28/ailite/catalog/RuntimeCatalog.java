package io.github.yinwenyuan28.ailite.catalog;

/**
 * 运行时目录。核心只依赖这个接口，JDBC 实现在可选模块里。
 *
 * <p>没有目录时使用 {@link NoopRuntimeCatalog}，解析仍走配置文件。</p>
 */
public interface RuntimeCatalog {

    /**
     * @return true 表示业务调用按数据库绑定解析，且供应商可以不在配置文件里写模型列表
     */
    boolean isActive();

    /**
     * @param providerId 供应商 id
     * @return 覆盖；没有覆盖时返回 null
     */
    ProviderOverlay findProvider(String providerId);

    /**
     * @param providerId 供应商 id
     * @param modelId    模型 id
     * @return 模型行；没有时返回 null。同一对键有多行时返回更新时间较新的那一行
     */
    CatalogModel findModel(String providerId, String modelId);

    /**
     * @param bizId 业务 id
     * @return 绑定；没有时返回 null
     */
    BizBinding findBinding(String bizId);

    /**
     * 清掉本机缓存。管理页保存后调用。
     */
    void invalidate();
}

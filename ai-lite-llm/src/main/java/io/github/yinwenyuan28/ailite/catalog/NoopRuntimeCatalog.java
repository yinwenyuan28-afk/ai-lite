package io.github.yinwenyuan28.ailite.catalog;

/**
 * 空目录。查询都返回 null，解析保持配置文件路径。
 */
public final class NoopRuntimeCatalog implements RuntimeCatalog {

    public static final NoopRuntimeCatalog INSTANCE = new NoopRuntimeCatalog();

    private NoopRuntimeCatalog() {
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public ProviderOverlay findProvider(String providerId) {
        return null;
    }

    @Override
    public CatalogModel findModel(String providerId, String modelId) {
        return null;
    }

    @Override
    public BizBinding findBinding(String bizId) {
        return null;
    }

    @Override
    public void invalidate() {
    }
}

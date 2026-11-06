package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时目录的表名和缓存。
 *
 * <p>四张表全部可通过配置改名，用于与既有库的表命名规范对齐（例如统一业务前缀）。
 * 表名只允许字母、数字和下划线，见 {@link JdbcRuntimeCatalog#safeTable}。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.catalog")
public class AiLiteCatalogProperties {

    private int cacheSeconds = 5;
    /**
     * 调用正文档位（{@code AI_LITE_ADMIN_SETTING} 的 {@code call-body} 行）的缓存秒数。
     *
     * <p>档位在每次调用记录时都要读一次，而它几小时才改一次，不缓存等于每次模型
     * 调用都多一条 SELECT。缓存后改档位最多延迟这个秒数生效；管理页写档位时会
     * 直接更新本机缓存，所以本机是立即生效的。</p>
     *
     * <p>小于等于 0 表示不缓存，退化为每次读库。</p>
     */
    private int settingCacheSeconds = 10;
    private String providerTable = "AI_LITE_PROVIDER_OVERLAY";
    private String modelTable = "AI_LITE_MODEL";
    private String bizTable = "AI_LITE_BIZ_BINDING";
    private String settingTable = "AI_LITE_ADMIN_SETTING";

    /**
     * 是否多节点部署。默认 {@code false}（单节点）。
     *
     * <p><b>为什么要显式声明</b>：管理页的「加入模型」是「先查再插」，两个请求同时
     * 处理同一对 {@code (供应商, 模型)} 时会插出两行。单节点在进程内加锁就能防住；
     * 多节点必须用 Redis 锁，否则两个节点各锁各的，等于没锁。</p>
     *
     * <p>这件事代码没法可靠地自动判断（同机多进程算不算？容器副本算几个？），
     * 所以要求显式配置。配成 {@code true} 时必须能拿到
     * {@code StringRedisTemplate}，否则启动失败——不能悄悄退回进程内锁，
     * 那样用户以为已经防住了，实际没有。</p>
     */
    private boolean multiNode = false;

    /**
     * 多节点模式下 Redis 锁的租期（毫秒）。
     *
     * <p>进程崩溃时锁不能永久残留，所以带这个过期时间。要明显长于一次写入
     * （几条 SQL）的耗时。</p>
     */
    private long lockLeaseMillis = 10000L;

    /**
     * 多节点模式下抢锁的最长等待（毫秒）。超时说明有别的节点长时间占着同一个模型的锁。
     */
    private long lockWaitMillis = 10000L;

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public String getProviderTable() {
        return providerTable;
    }

    public void setProviderTable(String providerTable) {
        this.providerTable = providerTable;
    }

    public String getModelTable() {
        return modelTable;
    }

    public void setModelTable(String modelTable) {
        this.modelTable = modelTable;
    }

    public String getBizTable() {
        return bizTable;
    }

    public void setBizTable(String bizTable) {
        this.bizTable = bizTable;
    }

    /**
     * @return 管理端运行时开关表名（目前只存 call-body 档位）
     */
    public String getSettingTable() {
        return settingTable;
    }

    public void setSettingTable(String settingTable) {
        this.settingTable = settingTable;
    }

    /**
     * @return 调用正文档位的缓存秒数；小于等于 0 表示不缓存
     */
    public int getSettingCacheSeconds() {
        return settingCacheSeconds;
    }

    public void setSettingCacheSeconds(int settingCacheSeconds) {
        this.settingCacheSeconds = settingCacheSeconds;
    }

    /**
     * @return true 表示多节点部署，目录写入用 Redis 锁
     */
    public boolean isMultiNode() {
        return multiNode;
    }

    public void setMultiNode(boolean multiNode) {
        this.multiNode = multiNode;
    }

    /**
     * @return Redis 锁租期（毫秒）
     */
    public long getLockLeaseMillis() {
        return lockLeaseMillis;
    }

    public void setLockLeaseMillis(long lockLeaseMillis) {
        this.lockLeaseMillis = lockLeaseMillis;
    }

    /**
     * @return 抢锁最长等待（毫秒）
     */
    public long getLockWaitMillis() {
        return lockWaitMillis;
    }

    public void setLockWaitMillis(long lockWaitMillis) {
        this.lockWaitMillis = lockWaitMillis;
    }
}

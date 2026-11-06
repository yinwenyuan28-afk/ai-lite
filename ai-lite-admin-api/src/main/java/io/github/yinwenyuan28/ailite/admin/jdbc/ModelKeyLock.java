package io.github.yinwenyuan28.ailite.admin.jdbc;

/**
 * 目录写入的互斥锁。
 *
 * <p><b>为什么需要它</b>：{@code insertModelIfAbsent} 是「先查再插」。两个请求同时
 * adopt 同一对 {@code (PROVIDER_ID, MODEL_ID)} 时都查不到、都插入，库里出现两行。
 * 之后 {@code saveCapabilities} 的 {@code UPDATE ... WHERE PROVIDER_ID=? AND MODEL_ID=?}
 * 会一次改掉全部重复行，而读取只认其中一行，表现为「保存成功、刷新又变回去」。</p>
 *
 * <p><b>为什么不用唯一索引</b>：加唯一索引确实能根治，但需要改表结构，且升级前
 * 已经存在重复行的库要人工清理才能建上。这里改成在写入路径上加锁，不动表结构。</p>
 *
 * <p><b>锁的边界由部署形态决定</b>，所以做成接口：</p>
 * <ul>
 *   <li>单节点：{@link LocalModelKeyLock}，JVM 内按 key 分段加锁，不加任何外部依赖。</li>
 *   <li>多节点：{@code RedisModelKeyLock}，Redis {@code SET NX PX}，跨进程生效。</li>
 * </ul>
 *
 * <p>实现必须满足两条：{@code withLock} 内的代码抛异常时也要释放锁；同一线程
 * 重复进入同一 key 不能死锁（实现允许直接放行或按重入计数处理）。</p>
 */
public interface ModelKeyLock {

    /**
     * 在锁内执行一段代码。
     *
     * @param providerId 供应商 id
     * @param modelId    模型 id
     * @param body       要执行的代码
     * @param <T>        返回值类型
     * @return body 的返回值
     */
    <T> T withLock(String providerId, String modelId, java.util.concurrent.Callable<T> body);
}

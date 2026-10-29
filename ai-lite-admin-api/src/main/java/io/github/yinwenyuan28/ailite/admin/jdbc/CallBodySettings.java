package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;

/**
 * 可读又可写的调用正文记录档位。
 *
 * <p>{@link CallBodyPolicy} 是网关侧只读契约（每次调用都要问一次"要不要记正文"），
 * 写入档位是管理台独有的运维动作，因此单独成接口，不往 llm 模块的 SPI 里加方法。</p>
 *
 * <p>实现必须与 {@link CallBodyPolicy} 共用同一张表——网关读的档位和管理台写的档位
 * 必须是同一个值，否则会出现"管理台显示已开启、实际不记录"。</p>
 */
public interface CallBodySettings extends CallBodyPolicy {

    /**
     * 保存档位。
     *
     * @param level none、failure 或 all
     * @throws IllegalArgumentException 档位取值非法
     * @throws IllegalStateException    设置表不存在
     */
    void save(String level);
}

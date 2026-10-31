package io.github.yinwenyuan28.ailite.admin.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用方异步支持体检开关。
 *
 * <p>放在 {@code ai-lite.admin} 下而不是新开前缀：这项体检是「管理/诊断」职责的一部分，
 * 和 admin 模块同生命周期，运维在一个地方就能关掉。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.admin.async-check")
public class AiLiteAsyncCheckProperties {

    /**
     * 是否启用体检。默认开启。
     *
     * <p>只打日志、不阻断启动，所以默认开是安全的；个别应用过滤器链特殊、
     * 不希望启动日志出现告警时可以关掉。</p>
     */
    private boolean enabled = true;

    /**
     * 是否把全部过滤器（含正常的）也打印出来。默认关闭。
     *
     * <p>排查时打开能一次看清整条链；平时只输出有问题的，避免日志噪音。</p>
     */
    private boolean verbose = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
}

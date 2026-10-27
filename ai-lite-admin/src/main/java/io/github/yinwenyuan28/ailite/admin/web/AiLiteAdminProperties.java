package io.github.yinwenyuan28.ailite.admin.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理接口开关。页面另外用 {@code page-enabled} 打开，默认不提供自带页面。
 */
@ConfigurationProperties(prefix = "ai-lite.admin")
public class AiLiteAdminProperties {

    private boolean enabled = false;
    private boolean pageEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPageEnabled() {
        return pageEnabled;
    }

    public void setPageEnabled(boolean pageEnabled) {
        this.pageEnabled = pageEnabled;
    }
}

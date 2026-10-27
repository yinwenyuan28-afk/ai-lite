package io.github.yinwenyuan28.ailite.admin.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理 REST 开关。自带页面在独立模块 {@code ai-lite-admin-ui} 里，另有 {@code page-enabled}。
 */
@ConfigurationProperties(prefix = "ai-lite.admin")
public class AiLiteAdminProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

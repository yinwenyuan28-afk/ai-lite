package io.github.yinwenyuan28.ailite.admin.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自带管理页开关。需同时引入 {@code ai-lite-admin-ui} 且打开管理 API。
 */
@ConfigurationProperties(prefix = "ai-lite.admin")
public class AiLiteAdminUiProperties {

    private boolean pageEnabled = false;

    public boolean isPageEnabled() {
        return pageEnabled;
    }

    public void setPageEnabled(boolean pageEnabled) {
        this.pageEnabled = pageEnabled;
    }
}

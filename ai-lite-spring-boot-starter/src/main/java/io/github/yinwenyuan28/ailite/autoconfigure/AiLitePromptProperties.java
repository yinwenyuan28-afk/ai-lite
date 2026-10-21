package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.prompt.PromptKey;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词中心配置。
 *
 * <p>key 采用 {@code ai-lite.prompt.keys[]} 数组形式登记；默认模板用于数据库里
 * 还没有生效版本时兜底，保证提示词中心故障不阻断业务。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.prompt")
public class AiLitePromptProperties {

    private boolean enabled = true;
    /** 生效版本本地缓存秒数；0 表示每次都读存储。 */
    private long cacheSeconds = 60L;
    private List<PromptKey> keys = new ArrayList<PromptKey>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(long cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public List<PromptKey> getKeys() {
        return keys;
    }

    public void setKeys(List<PromptKey> keys) {
        this.keys = keys == null ? new ArrayList<PromptKey>() : keys;
    }
}

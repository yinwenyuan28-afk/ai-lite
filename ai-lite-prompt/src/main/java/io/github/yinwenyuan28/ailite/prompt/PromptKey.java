package io.github.yinwenyuan28.ailite.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个登记过的提示词。key 是稳定标识，模板默认值用于数据库里还没有生效版本时兜底。
 *
 * <p>这个类同时作为 Starter 的配置绑定目标，因此保留无参构造和 setter。</p>
 */
public final class PromptKey {

    private String key;
    private String title;
    private List<String> placeholders = new ArrayList<String>();
    private String bizId;
    private String profile;
    /** 数据库不可用或尚未发布时使用的系统模板。 */
    private String defaultSystemTemplate;
    /** 数据库不可用或尚未发布时使用的用户模板。 */
    private String defaultUserTemplate;
    /** 目录模式的业务提示词。未发布时的文案与配置登记项不同。 */
    private boolean catalogBound;

    public PromptKey() {
    }

    public PromptKey(String key, String title, List<String> placeholders,
                     String defaultSystemTemplate, String defaultUserTemplate) {
        this.key = key;
        this.title = title;
        setPlaceholders(placeholders);
        this.defaultSystemTemplate = defaultSystemTemplate;
        this.defaultUserTemplate = defaultUserTemplate;
    }

    /**
     * @return 深拷贝，避免调用方修改目录内的对象
     */
    public PromptKey copy() {
        PromptKey copy = new PromptKey();
        copy.key = key;
        copy.title = title;
        copy.placeholders = getPlaceholders();
        copy.bizId = bizId;
        copy.profile = profile;
        copy.defaultSystemTemplate = defaultSystemTemplate;
        copy.defaultUserTemplate = defaultUserTemplate;
        copy.catalogBound = catalogBound;
        return copy;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getPlaceholders() {
        return placeholders;
    }

    public void setPlaceholders(List<String> placeholders) {
        this.placeholders = placeholders == null
                ? new ArrayList<String>() : new ArrayList<String>(placeholders);
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getDefaultSystemTemplate() {
        return defaultSystemTemplate;
    }

    public void setDefaultSystemTemplate(String defaultSystemTemplate) {
        this.defaultSystemTemplate = defaultSystemTemplate;
    }

    public String getDefaultUserTemplate() {
        return defaultUserTemplate;
    }

    public void setDefaultUserTemplate(String defaultUserTemplate) {
        this.defaultUserTemplate = defaultUserTemplate;
    }

    public boolean isCatalogBound() {
        return catalogBound;
    }

    public void setCatalogBound(boolean catalogBound) {
        this.catalogBound = catalogBound;
    }
}

package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读取业务 id 白名单。与 starter 里的 {@code ai-lite.llm} 绑定互不覆盖字段。
 */
@ConfigurationProperties(prefix = "ai-lite.llm")
public class AiLiteLlmBizProperties {

    private List<String> bizIds = new ArrayList<String>();

    public List<String> getBizIds() {
        return bizIds;
    }

    public void setBizIds(List<String> bizIds) {
        this.bizIds = bizIds == null ? new ArrayList<String>() : bizIds;
    }
}

package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptKey;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录模式下的业务 id 必须能当作提示词 key。
 */
final class CatalogBizIds {

    static final String INVALID_BIZ = "业务 id 不符合提示词编码: ";

    private CatalogBizIds() {
    }

    static List<String> normalize(List<String> raw) {
        List<String> ids = new ArrayList<String>();
        if (raw == null) {
            return ids;
        }
        for (String id : raw) {
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            String key = id.trim();
            if (!PromptCatalog.validKey(key)) {
                throw new IllegalStateException(INVALID_BIZ + key);
            }
            ids.add(key);
        }
        return ids;
    }

    static PromptCatalog catalog(List<String> bizIds) {
        List<String> ids = normalize(bizIds);
        List<PromptKey> keys = new ArrayList<PromptKey>();
        for (String id : ids) {
            PromptKey key = new PromptKey();
            key.setKey(id);
            key.setTitle(id);
            key.setCatalogBound(true);
            keys.add(key);
        }
        return new PromptCatalog(keys);
    }
}

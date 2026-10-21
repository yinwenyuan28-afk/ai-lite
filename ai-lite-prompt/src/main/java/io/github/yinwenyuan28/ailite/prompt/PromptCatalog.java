package io.github.yinwenyuan28.ailite.prompt;

import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 已登记的提示词目录。重复 key 或非法 key 在启动阶段就失败。
 */
public final class PromptCatalog {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final Map<String, PromptKey> byKey = new LinkedHashMap<String, PromptKey>();

    /**
     * @param keys 配置里的提示词登记项
     */
    public PromptCatalog(List<PromptKey> keys) {
        if (keys == null) {
            return;
        }
        for (PromptKey item : keys) {
            if (item == null || Strings.isBlank(item.getKey())) {
                continue;
            }
            String key = item.getKey().trim();
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw new PromptException("提示词编码只能用小写字母、数字和短横线: " + key);
            }
            if (byKey.containsKey(key)) {
                throw new PromptException("提示词编码重复: " + key);
            }
            PromptKey copy = item.copy();
            copy.setKey(key);
            byKey.put(key, copy);
        }
    }

    /**
     * @return 登记项副本，保持配置顺序
     */
    public List<PromptKey> keys() {
        List<PromptKey> result = new ArrayList<PromptKey>();
        for (PromptKey item : byKey.values()) {
            result.add(item.copy());
        }
        return result;
    }

    /**
     * @param promptKey 提示词 key
     * @return 登记项；未登记时返回 null
     */
    public PromptKey find(String promptKey) {
        String key = Strings.trimToNull(promptKey);
        return key == null ? null : byKey.get(key);
    }

    /**
     * @param promptKey 提示词 key
     * @return 登记项
     * @throws PromptException 未登记时抛出
     */
    public PromptKey require(String promptKey) {
        String key = Strings.trimToNull(promptKey);
        if (key == null) {
            throw new PromptException("请填写提示词编码");
        }
        PromptKey spec = byKey.get(key);
        if (spec == null) {
            throw new PromptException("这个提示词还没有在配置里登记: " + key);
        }
        return spec;
    }

    /**
     * @param key 待校验的编码
     * @return true 表示格式合法
     */
    public static boolean validKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }
}

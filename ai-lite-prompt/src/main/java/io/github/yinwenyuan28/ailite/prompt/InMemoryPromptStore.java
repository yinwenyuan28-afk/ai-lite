package io.github.yinwenyuan28.ailite.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 有界内存实现。默认给 Starter 用，不需要数据库即可跑通草稿、发布和回滚。
 *
 * <p>所有读写都在实例锁内完成，读返回副本，避免调用方改到内部状态。
 * 多个节点部署时应换成 JDBC 等共享存储。</p>
 */
public final class InMemoryPromptStore implements PromptStore {

    private final Map<String, Map<String, PromptVersion>> versions =
            new LinkedHashMap<String, Map<String, PromptVersion>>();
    private final Map<String, Active> active = new LinkedHashMap<String, Active>();
    private final Map<String, List<PromptAudit>> audits =
            new LinkedHashMap<String, List<PromptAudit>>();

    @Override
    public synchronized Optional<PromptVersion> findActive(String promptKey) {
        Active pointer = active.get(promptKey);
        if (pointer == null) {
            return Optional.empty();
        }
        Map<String, PromptVersion> byId = versions.get(promptKey);
        if (byId == null) {
            return Optional.empty();
        }
        PromptVersion version = byId.get(pointer.versionId);
        return version == null ? Optional.<PromptVersion>empty() : Optional.of(version.copy());
    }

    @Override
    public synchronized Optional<PromptVersion> findVersion(String versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        for (Map<String, PromptVersion> byId : versions.values()) {
            PromptVersion version = byId.get(versionId);
            if (version != null) {
                return Optional.of(version.copy());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<PromptVersion> versions(String promptKey, int limit) {
        List<PromptVersion> result = new ArrayList<PromptVersion>();
        Map<String, PromptVersion> byId = versions.get(promptKey);
        if (byId == null) {
            return result;
        }
        for (PromptVersion version : byId.values()) {
            result.add(version.copy());
        }
        Collections.sort(result, new Comparator<PromptVersion>() {
            @Override
            public int compare(PromptVersion left, PromptVersion right) {
                return Integer.compare(right.getVersionNo(), left.getVersionNo());
            }
        });
        return trim(result, limit);
    }

    @Override
    public synchronized List<PromptAudit> audits(String promptKey, int limit) {
        List<PromptAudit> rows = audits.get(promptKey);
        List<PromptAudit> result = new ArrayList<PromptAudit>();
        if (rows != null) {
            for (PromptAudit row : rows) {
                result.add(row.copy());
            }
        }
        return trim(result, limit);
    }

    @Override
    public synchronized int nextVersionNo(String promptKey) {
        Map<String, PromptVersion> byId = versions.get(promptKey);
        int max = 0;
        if (byId != null) {
            for (PromptVersion version : byId.values()) {
                max = Math.max(max, version.getVersionNo());
            }
        }
        return max + 1;
    }

    @Override
    public synchronized void saveVersion(PromptVersion version) {
        if (version == null || version.getVersionId() == null || version.getPromptKey() == null) {
            throw new PromptException("提示词版本缺少 id 或 key");
        }
        Map<String, PromptVersion> byId = versions.get(version.getPromptKey());
        if (byId == null) {
            byId = new LinkedHashMap<String, PromptVersion>();
            versions.put(version.getPromptKey(), byId);
        }
        byId.put(version.getVersionId(), version.copy());
    }

    @Override
    public synchronized void activate(String promptKey, String versionId, String actor, Date publishTime) {
        Map<String, PromptVersion> byId = versions.get(promptKey);
        PromptVersion target = byId == null ? null : byId.get(versionId);
        if (target == null) {
            throw new PromptException("提示词版本不存在");
        }
        for (PromptVersion version : byId.values()) {
            if (version.getStatus() == PromptStatus.PUBLISHED
                    && !version.getVersionId().equals(versionId)) {
                version.setStatus(PromptStatus.RETIRED);
            }
        }
        target.setStatus(PromptStatus.PUBLISHED);
        Active pointer = active.get(promptKey);
        int revision = pointer == null ? 1 : pointer.revision + 1;
        active.put(promptKey, new Active(versionId, revision, publishTime, actor));
    }

    @Override
    public synchronized void audit(PromptAudit audit) {
        if (audit == null || audit.getPromptKey() == null) {
            return;
        }
        List<PromptAudit> rows = audits.get(audit.getPromptKey());
        if (rows == null) {
            rows = new ArrayList<PromptAudit>();
            audits.put(audit.getPromptKey(), rows);
        }
        rows.add(audit.copy());
    }

    private static <T> List<T> trim(List<T> rows, int limit) {
        if (limit <= 0 || rows.size() <= limit) {
            return rows;
        }
        return new ArrayList<T>(rows.subList(0, limit));
    }

    private static final class Active {
        private final String versionId;
        private final int revision;
        private final Date publishTime;
        private final String actor;

        private Active(String versionId, int revision, Date publishTime, String actor) {
            this.versionId = versionId;
            this.revision = revision;
            this.publishTime = publishTime;
            this.actor = actor;
        }
    }
}

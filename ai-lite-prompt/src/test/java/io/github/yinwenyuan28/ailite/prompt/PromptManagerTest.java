package io.github.yinwenyuan28.ailite.prompt;

import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提示词目录、版本流转、缓存降级和渲染规则。
 */
public class PromptManagerTest {

    private static PromptCatalog catalog() {
        PromptKey key = new PromptKey("resume-parse", "简历解析",
                Arrays.asList("resume", "job"), "系统模板 {{resume}}", "岗位 {{job}}");
        return new PromptCatalog(Collections.singletonList(key));
    }

    private static PromptManager manager() {
        return new PromptManager(catalog(), new InMemoryPromptStore(), 0L);
    }

    @Test
    public void catalogRejectsDuplicateKey() {
        PromptKey first = new PromptKey("a", "a", null, "s", null);
        PromptKey second = new PromptKey("a", "b", null, "s", null);

        assertThatThrownBy(() -> new PromptCatalog(Arrays.asList(first, second)))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("重复");
    }

    @Test
    public void catalogRejectsInvalidKey() {
        PromptKey key = new PromptKey("Bad_Key", "bad", null, "s", null);

        assertThatThrownBy(() -> new PromptCatalog(Collections.singletonList(key)))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("小写字母");
    }

    @Test
    public void catalogSkipsBlankEntries() {
        PromptCatalog catalog = new PromptCatalog(Arrays.asList(null,
                new PromptKey(" ", "blank", null, "s", null),
                new PromptKey("ok", "ok", null, "s", null)));

        assertThat(catalog.keys()).hasSize(1);
        assertThat(catalog.keys().get(0).getKey()).isEqualTo("ok");
    }

    @Test
    public void draftPublishAndCurrent() {
        PromptManager manager = manager();

        PromptVersion draft = manager.createDraft("resume-parse",
                "系统 {{resume}}", "岗位 {{job}}", "{\"type\":\"object\"}", "alice");

        assertThat(draft.getVersionNo()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(PromptStatus.DRAFT);
        assertThat(draft.getContentHash()).hasSize(64);
        assertThat(manager.current("resume-parse")).isEmpty();

        PromptVersion published = manager.publish(draft.getVersionId(), "bob");

        assertThat(published.getStatus()).isEqualTo(PromptStatus.PUBLISHED);
        Optional<PromptVersion> active = manager.current("resume-parse");
        assertThat(active).isPresent();
        assertThat(active.get().getVersionId()).isEqualTo(draft.getVersionId());
    }

    @Test
    public void publishRetiresPreviousVersion() {
        PromptManager manager = manager();
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");
        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}} {{job}}", null, null, "a");

        assertThat(second.getVersionNo()).isEqualTo(2);
        manager.publish(second.getVersionId(), "a");

        List<PromptVersion> versions = manager.versions("resume-parse");
        assertThat(versions).hasSize(2);
        assertThat(statusOf(versions, first.getVersionId())).isEqualTo(PromptStatus.RETIRED);
        assertThat(statusOf(versions, second.getVersionId())).isEqualTo(PromptStatus.PUBLISHED);
        assertThat(manager.current("resume-parse").get().getVersionId())
                .isEqualTo(second.getVersionId());
    }

    @Test
    public void saveDraftOnlyAllowsDraft() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        manager.publish(draft.getVersionId(), "a");

        assertThatThrownBy(() -> manager.saveDraft(draft.getVersionId(), "v2", null, null, "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    public void saveDraftUpdatesHash() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse", "v1", null, null, "a");
        String before = draft.getContentHash();

        PromptVersion saved = manager.saveDraft(draft.getVersionId(), "v2", "u", null, "a");

        assertThat(saved.getContentHash()).isNotEqualTo(before);
        assertThat(saved.getSystemTemplate()).isEqualTo("v2");
        assertThat(saved.getUserTemplate()).isEqualTo("u");
    }

    @Test
    public void publishRequiresConfiguredPlaceholders() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse", "没有占位符", null, null, "a");

        assertThatThrownBy(() -> manager.publish(draft.getVersionId(), "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("占位符");
        assertThat(manager.current("resume-parse")).isEmpty();
    }

    @Test
    public void rollbackRestoresRetiredVersion() {
        PromptManager manager = manager();
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");
        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}} {{job}}", null, null, "a");
        manager.publish(second.getVersionId(), "a");

        manager.rollback(first.getVersionId(), "a");

        assertThat(manager.current("resume-parse").get().getVersionId())
                .isEqualTo(first.getVersionId());
        List<PromptVersion> versions = manager.versions("resume-parse");
        assertThat(statusOf(versions, first.getVersionId())).isEqualTo(PromptStatus.PUBLISHED);
        assertThat(statusOf(versions, second.getVersionId())).isEqualTo(PromptStatus.RETIRED);
    }

    @Test
    public void rollbackRejectsDraftAndActiveVersion() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");

        assertThatThrownBy(() -> manager.rollback(draft.getVersionId(), "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("草稿");

        manager.publish(draft.getVersionId(), "a");
        assertThatThrownBy(() -> manager.rollback(draft.getVersionId(), "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("当前生效");
    }

    @Test
    public void renderUsesActiveTemplate() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse",
                "简历={{resume}}", "岗位={{job}}", null, "a");
        manager.publish(draft.getVersionId(), "a");

        Map<String, String> vars = new HashMap<String, String>();
        vars.put("resume", "五年 Java");
        vars.put("job", "后端");
        RenderedPrompt rendered = manager.render("resume-parse", vars);

        assertThat(rendered.getSystem()).isEqualTo("简历=五年 Java");
        assertThat(rendered.getUser()).isEqualTo("岗位=后端");
        assertThat(rendered.getMessages()).hasSize(2);
        assertThat(rendered.getMessages().get(0).getRole()).isEqualTo(LlmRole.SYSTEM);
    }

    @Test
    public void renderFallsBackToConfiguredDefaults() {
        PromptManager manager = manager();

        RenderedPrompt rendered = manager.render("resume-parse", Collections.singletonMap("job", "后端"));

        assertThat(rendered.getSystem()).isEqualTo("系统模板 {{resume}}");
        assertThat(rendered.getUser()).isEqualTo("岗位 后端");
    }

    @Test
    public void renderPrefersExplicitFallbackOverDefaults() {
        PromptManager manager = manager();

        RenderedPrompt rendered = manager.render("resume-parse", null, "调用方系统", "调用方用户");

        assertThat(rendered.getSystem()).isEqualTo("调用方系统");
        assertThat(rendered.getUser()).isEqualTo("调用方用户");
    }

    @Test
    public void renderRejectsMissingTemplate() {
        PromptCatalog catalog = new PromptCatalog(Collections.singletonList(
                new PromptKey("empty", "empty", null, null, null)));
        PromptManager manager = new PromptManager(catalog);

        assertThatThrownBy(() -> manager.render("empty", null))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    public void catalogBoundKeyWithoutActiveVersionSaysUnpublished() {
        PromptKey key = new PromptKey("demo-chat", "demo", null, null, null);
        key.setCatalogBound(true);
        PromptManager manager = new PromptManager(new PromptCatalog(Collections.singletonList(key)));

        assertThatThrownBy(() -> manager.render("demo-chat", null))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("提示词未发布: demo-chat");
    }

    @Test
    public void catalogBoundStoreFailureIsNotReportedAsUnpublished() {
        PromptKey key = new PromptKey("demo-chat", "demo", null, null, null);
        key.setCatalogBound(true);
        PromptStore store = new PromptStore() {
            @Override
            public Optional<PromptVersion> findActive(String promptKey) {
                throw new IllegalStateException("down");
            }

            @Override
            public Optional<PromptVersion> findVersion(String versionId) {
                return Optional.empty();
            }

            @Override
            public List<PromptVersion> versions(String promptKey, int limit) {
                return Collections.emptyList();
            }

            @Override
            public List<PromptAudit> audits(String promptKey, int limit) {
                return Collections.emptyList();
            }

            @Override
            public int nextVersionNo(String promptKey) {
                return 1;
            }

            @Override
            public void saveVersion(PromptVersion version) {
            }

            @Override
            public void activate(String promptKey, String versionId, String actor, Date publishTime) {
            }

            @Override
            public void audit(PromptAudit audit) {
            }
        };
        PromptManager manager = new PromptManager(new PromptCatalog(Collections.singletonList(key)), store, 0L);

        assertThatThrownBy(() -> manager.render("demo-chat", null))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("提示词存储不可用")
                .matches(ex -> !ex.getMessage().contains("未发布"));
    }

    @Test
    public void renderDoesNotEscapeUnknownPlaceholders() {
        PromptManager manager = manager();

        RenderedPrompt rendered = manager.render("resume-parse",
                Collections.singletonMap("job", "后端"));

        assertThat(rendered.getSystem()).contains("{{resume}}");
    }

    @Test
    public void storeFailureFallsBackToDefaultsWithoutThrowing() {
        PromptStore broken = new BrokenStore();
        PromptManager manager = new PromptManager(catalog(), broken, 0L);

        assertThat(manager.current("resume-parse")).isEmpty();
        RenderedPrompt rendered = manager.render("resume-parse", Collections.singletonMap("job", "x"));
        assertThat(rendered.getSystem()).isEqualTo("系统模板 {{resume}}");
    }

    @Test
    public void publishInvalidatesCacheImmediately() {
        InMemoryPromptStore store = new InMemoryPromptStore();
        PromptManager manager = new PromptManager(catalog(), store, 3600L);
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");
        assertThat(manager.current("resume-parse").get().getSystemTemplate()).isEqualTo("v1 {{resume}} {{job}}");

        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}} {{job}}", null, null, "a");
        manager.publish(second.getVersionId(), "a");

        assertThat(manager.current("resume-parse").get().getSystemTemplate()).isEqualTo("v2 {{resume}} {{job}}");
    }

    @Test
    public void cacheExpiresAndSeesExternalPublish() throws Exception {
        InMemoryPromptStore store = new InMemoryPromptStore();
        PromptCatalog catalog = catalog();
        PromptManager reader = new PromptManager(catalog, store, 1L);
        PromptManager writer = new PromptManager(catalog, store, 0L);
        PromptVersion first = writer.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        writer.publish(first.getVersionId(), "a");
        assertThat(reader.current("resume-parse").get().getSystemTemplate()).contains("v1");

        PromptVersion second = writer.createDraft("resume-parse", "v2 {{resume}} {{job}}", null, null, "a");
        writer.publish(second.getVersionId(), "a");
        assertThat(reader.current("resume-parse").get().getSystemTemplate()).contains("v1");

        Thread.sleep(1100L);
        assertThat(reader.current("resume-parse").get().getSystemTemplate()).contains("v2");
    }

    @Test
    public void auditsRecordEveryAction() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a");
        manager.saveDraft(draft.getVersionId(), "v1b {{resume}} {{job}}", null, null, "a");
        manager.publish(draft.getVersionId(), "a");
        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}} {{job}}", null, null, "a");
        manager.publish(second.getVersionId(), "a");
        manager.rollback(draft.getVersionId(), "a");

        List<PromptAudit> audits = manager.audits("resume-parse");
        assertThat(audits).extracting("action").containsExactly(
                PromptActions.DRAFT, PromptActions.UPDATE, PromptActions.PUBLISH,
                PromptActions.DRAFT, PromptActions.PUBLISH, PromptActions.ROLLBACK);
    }

    @Test
    public void auditDetailCarriesExplicitMessage() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse",
                "v1 {{resume}} {{job}}", null, null, "a", "首次起草，等待业务确认");
        manager.saveDraft(draft.getVersionId(), "v1b {{resume}} {{job}}", null, null, "a",
                "把要求从本科改为大专");

        List<PromptAudit> audits = manager.audits("resume-parse");
        assertThat(audits).extracting("detail").containsExactly(
                "首次起草，等待业务确认", "把要求从本科改为大专");
    }

    @Test
    public void auditDetailFallsBackToAutoSummary() {
        PromptManager manager = manager();
        PromptVersion first = manager.createDraft("resume-parse",
                "v1 {{resume}} {{job}}", "u1 {{job}}", "schema", "a");
        manager.publish(first.getVersionId(), "a");

        PromptVersion second = manager.createDraft("resume-parse", "  ", null, null, "a");
        manager.saveDraft(second.getVersionId(), "v2 {{resume}} {{job}}", "u1 {{job}}", "schema", "a");
        manager.publish(second.getVersionId(), "a");
        manager.rollback(first.getVersionId(), "a");

        List<PromptAudit> audits = manager.audits("resume-parse");
        assertThat(audits).extracting("detail").containsExactly(
                "手动填写模板",
                "首次发布生效",
                "从第 1 版继承",
                "系统模板",
                "接替第 1 版生效",
                "从第 2 版回滚");
    }

    @Test
    public void auditDetailTruncatesOversizedMessage() {
        PromptManager manager = manager();
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longMessage.append('x');
        }

        manager.createDraft("resume-parse", "v1 {{resume}} {{job}}", null, null, "a",
                longMessage.toString());

        List<PromptAudit> audits = manager.audits("resume-parse");
        assertThat(audits.get(0).getDetail()).hasSize(PromptManager.DETAIL_LIMIT);
    }

    @Test
    public void auditDetailIsNeverNull() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse",
                "v1 {{resume}} {{job}}", null, null, "a");
        manager.saveDraft(draft.getVersionId(), "v1 {{resume}} {{job}}", null, null, "a");
        manager.publish(draft.getVersionId(), "a");

        List<PromptAudit> audits = manager.audits("resume-parse");
        assertThat(audits).extracting("detail").doesNotContainNull();
        assertThat(audits.get(0).getDetail()).isEqualTo("手动填写模板");
        assertThat(audits.get(1).getDetail()).isEqualTo("内容未变化");
    }

    @Test
    public void createdDraftInheritsActiveWhenSystemBlank() {
        PromptManager manager = manager();
        PromptVersion first = manager.createDraft("resume-parse",
                "v1 {{resume}}", "u1 {{job}}", "schema", "a");
        manager.publish(first.getVersionId(), "a");

        PromptVersion second = manager.createDraft("resume-parse", "  ", null, null, "a");

        assertThat(second.getSystemTemplate()).isEqualTo("v1 {{resume}}");
        assertThat(second.getUserTemplate()).isEqualTo("u1 {{job}}");
        assertThat(second.getOutputSchema()).isEqualTo("schema");
    }

    @Test
    public void rejectsOversizedTemplates() {
        PromptManager manager = manager();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < PromptManager.TEXT_LIMIT + 1; i++) {
            huge.append('x');
        }

        assertThatThrownBy(() -> manager.createDraft("resume-parse", huge.toString(), null, null, "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("过长");
    }

    @Test
    public void unregisteredKeyIsRejected() {
        PromptManager manager = manager();

        assertThatThrownBy(() -> manager.current("not-registered"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("没有在配置里登记");
        assertThatThrownBy(() -> manager.createDraft("not-registered", "s", null, null, "a"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("没有在配置里登记");
    }

    @Test
    public void keysReturnsDefensiveCopies() {
        PromptCatalog catalog = catalog();
        PromptManager manager = new PromptManager(catalog);

        List<PromptKey> keys = manager.keys();
        keys.get(0).setDefaultSystemTemplate("changed");

        assertThat(manager.keys().get(0).getDefaultSystemTemplate()).isEqualTo("系统模板 {{resume}}");
    }

    @Test
    public void concurrentDraftsGetUniqueVersionNumbers() throws Exception {
        final PromptManager manager = manager();
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<PromptVersion> created = Collections.synchronizedList(new ArrayList<PromptVersion>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            created.add(manager.createDraft("resume-parse", "s {{resume}} {{job}}", null, null, "a"));
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Set<Integer> numbers = new HashSet<Integer>();
        for (PromptVersion version : created) {
            numbers.add(Integer.valueOf(version.getVersionNo()));
        }
        assertThat(numbers).hasSize(threads);
    }

    @Test
    public void renderedMessagesAreImmutableAndOrdered() {
        RenderedPrompt rendered = new RenderedPrompt("sys", "user");

        List<LlmMessage> messages = rendered.getMessages();
        assertThat(messages).extracting("role").containsExactly(LlmRole.SYSTEM, LlmRole.USER);
        assertThatThrownBy(() -> messages.add(LlmMessage.user("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void unicodeTemplatesSurvivePublishAndRender() {
        PromptManager manager = manager();
        PromptVersion draft = manager.createDraft("resume-parse",
                "请解析 {{resume}}，输出中文摘要", "岗位：{{job}}", null, "张三");
        manager.publish(draft.getVersionId(), "张三");

        RenderedPrompt rendered = manager.render("resume-parse",
                vars("resume", "三年经验", "job", "后端开发"));

        assertThat(rendered.getSystem()).isEqualTo("请解析 三年经验，输出中文摘要");
        assertThat(rendered.getUser()).isEqualTo("岗位：后端开发");
    }

    private static Map<String, String> vars(String... pairs) {
        Map<String, String> vars = new HashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            vars.put(pairs[i], pairs[i + 1]);
        }
        return vars;
    }

    private static PromptStatus statusOf(List<PromptVersion> versions, String versionId) {
        for (PromptVersion version : versions) {
            if (version.getVersionId().equals(versionId)) {
                return version.getStatus();
            }
        }
        throw new AssertionError("version not found: " + versionId);
    }

    /**
     * 所有操作都失败的存储，用于验证业务降级。
     */
    private static final class BrokenStore implements PromptStore {

        @Override
        public Optional<PromptVersion> findActive(String promptKey) {
            throw new IllegalStateException("db down");
        }

        @Override
        public Optional<PromptVersion> findVersion(String versionId) {
            throw new IllegalStateException("db down");
        }

        @Override
        public List<PromptVersion> versions(String promptKey, int limit) {
            throw new IllegalStateException("db down");
        }

        @Override
        public List<PromptAudit> audits(String promptKey, int limit) {
            throw new IllegalStateException("db down");
        }

        @Override
        public int nextVersionNo(String promptKey) {
            throw new IllegalStateException("db down");
        }

        @Override
        public void saveVersion(PromptVersion version) {
            throw new IllegalStateException("db down");
        }

        @Override
        public void activate(String promptKey, String versionId, String actor, Date publishTime) {
            throw new IllegalStateException("db down");
        }

        @Override
        public void audit(PromptAudit audit) {
            throw new IllegalStateException("db down");
        }
    }
}

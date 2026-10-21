package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptAudit;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptException;
import io.github.yinwenyuan28.ailite.prompt.PromptKey;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStatus;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC 提示词存储：版本号、发布指针、审计、限制条数和事务回滚。
 */
public class JdbcPromptStoreTest {

    private JdbcTemplate jdbc;
    private JdbcPromptStore store;
    private PromptManager manager;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:prompt-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        executeSchema(jdbc);
        store = new JdbcPromptStore(jdbc);
        PromptKey key = new PromptKey("resume-parse", "简历解析",
                Collections.singletonList("resume"),
                "默认系统 {{resume}}", "默认用户");
        manager = new PromptManager(new PromptCatalog(Collections.singletonList(key)), store, 0L);
    }

    @Test
    public void publishAndReadBack() {
        PromptVersion draft = manager.createDraft("resume-parse",
                "解析 {{resume}}", "岗位", "{}", "alice");
        manager.publish(draft.getVersionId(), "bob");

        Optional<PromptVersion> active = store.findActive("resume-parse");
        assertThat(active).isPresent();
        assertThat(active.get().getSystemTemplate()).isEqualTo("解析 {{resume}}");
        assertThat(active.get().getStatus()).isEqualTo(PromptStatus.PUBLISHED);
        assertThat(active.get().getCreatedBy()).isEqualTo("alice");
        assertThat(store.findActive("unknown")).isEmpty();
    }

    @Test
    public void publishRetiresOldVersionAndBumpsPointer() {
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");
        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}}", null, null, "a");
        manager.publish(second.getVersionId(), "a");

        List<PromptVersion> versions = store.versions("resume-parse", 50);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getVersionNo()).isEqualTo(2);
        assertThat(versions.get(0).getStatus()).isEqualTo(PromptStatus.PUBLISHED);
        assertThat(versions.get(1).getStatus()).isEqualTo(PromptStatus.RETIRED);

        Integer revision = jdbc.queryForObject(
                "SELECT REVISION FROM AI_LITE_PROMPT_ACTIVE WHERE PROMPT_KEY = ?",
                new Object[]{"resume-parse"}, Integer.class);
        assertThat(revision).isEqualTo(2);
    }

    @Test
    public void activateMissingVersionFailsAndRollsBack() {
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");

        JdbcPromptStore transactional = new JdbcPromptStore(jdbc,
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transactional.activate("resume-parse", "missing", "a", new java.util.Date()))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("版本不存在");

        assertThat(store.findActive("resume-parse").get().getVersionId())
                .isEqualTo(first.getVersionId());
        assertThat(store.findVersion(first.getVersionId()).get().getStatus())
                .isEqualTo(PromptStatus.PUBLISHED);
    }

    @Test
    public void saveVersionUpdatesExistingRow() {
        PromptVersion version = new PromptVersion();
        version.setVersionId("v1");
        version.setPromptKey("resume-parse");
        version.setVersionNo(1);
        version.setStatus(PromptStatus.DRAFT);
        version.setSystemTemplate("old");
        store.saveVersion(version);

        version.setSystemTemplate("new");
        store.saveVersion(version);

        assertThat(store.findVersion("v1").get().getSystemTemplate()).isEqualTo("new");
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM AI_LITE_PROMPT_VERSION",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    public void versionsAndAuditsRespectLimit() {
        for (int i = 0; i < 5; i++) {
            PromptVersion draft = manager.createDraft("resume-parse", "v" + i + " {{resume}}",
                    null, null, "a");
            manager.publish(draft.getVersionId(), "a");
        }

        assertThat(store.versions("resume-parse", 2)).hasSize(2);
        assertThat(store.versions("resume-parse", 0)).hasSize(5);

        List<PromptAudit> audits = store.audits("resume-parse", 3);
        assertThat(audits).hasSize(3);
        assertThat(audits.get(0).getAction()).isEqualTo("draft");
        assertThat(store.audits("resume-parse", 0)).hasSize(10);
    }

    @Test
    public void rollbackWorksThroughJdbcStore() {
        PromptVersion first = manager.createDraft("resume-parse", "v1 {{resume}}", null, null, "a");
        manager.publish(first.getVersionId(), "a");
        PromptVersion second = manager.createDraft("resume-parse", "v2 {{resume}}", null, null, "a");
        manager.publish(second.getVersionId(), "a");

        manager.rollback(first.getVersionId(), "a");

        assertThat(store.findActive("resume-parse").get().getVersionId())
                .isEqualTo(first.getVersionId());
    }

    @Test
    public void unicodeAndLargeTemplateSurviveRoundTrip() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            large.append("中文");
        }
        PromptVersion draft = manager.createDraft("resume-parse", large + " {{resume}}", null, null, "张三");
        manager.publish(draft.getVersionId(), "张三");

        assertThat(store.findActive("resume-parse").get().getSystemTemplate()).hasSize(large.length() + 11);
    }

    @Test
    public void nextVersionNoStartsAtOne() {
        assertThat(store.nextVersionNo("resume-parse")).isEqualTo(1);
    }

    @Test
    public void illegalTableNameIsRejected() {
        assertThatThrownBy(() -> new JdbcPromptStore(jdbc, "AI_LITE_PROMPT_VERSION; DROP TABLE x",
                "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void executeSchema(JdbcTemplate jdbc) throws Exception {
        InputStream stream = JdbcPromptStoreTest.class.getResourceAsStream("/ai-lite-prompt.sql");
        assertThat(stream).isNotNull();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        stream.close();
        String sql = new String(out.toByteArray(), StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            if (!statement.trim().isEmpty()) {
                jdbc.execute(statement);
            }
        }
    }
}

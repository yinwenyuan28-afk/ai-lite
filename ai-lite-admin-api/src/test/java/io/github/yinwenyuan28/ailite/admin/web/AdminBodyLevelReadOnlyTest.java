package io.github.yinwenyuan28.ailite.admin.web;

import io.github.yinwenyuan28.ailite.admin.jdbc.CallBodySettings;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcCallBodyPolicy;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalogTest;
import io.github.yinwenyuan28.ailite.admin.jdbc.SqliteFiles;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户自带档位策略时，管理页的读与写。
 *
 * <p><b>回归背景</b>：{@code @ConditionalOnMissingBean(CallBodyPolicy.class)} 让
 * {@code JdbcCallBodyPolicy} 在用户声明同类型 Bean 后不再创建，而管理页注入的是
 * {@code CallBodySettings}，于是取到 null：页面恒显示 none、保存直接报「不可用」，
 * 而网关实际用的是用户的策略。</p>
 *
 * <p><b>为什么不能靠改注解解决</b>：把条件改成 {@code CallBodySettings} 会让容器里
 * 同时出现用户的 {@code CallBodyPolicy} 与 {@code JdbcCallBodyPolicy}，
 * 而网关用 {@code getBeanProvider(...).getIfAvailable()} 取，多候选抛
 * {@code NoUniqueBeanDefinitionException}，应用起不来。所以这里改成
 * 「读走真实策略、写不了要说清原因」。</p>
 */
public class AdminBodyLevelReadOnlyTest {

    /**
     * 只有只读策略时：页面要显示真实档位，保存要给出明确原因。
     */
    @Test
    public void readOnlyPolicyIsShownAndSaveExplainsWhy() throws Exception {
        JdbcTemplate jdbc = SqliteFiles.open("body-level-readonly");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        // 用户自定义的只读档位：网关用的就是它。
        CallBodyPolicy userPolicy = new CallBodyPolicy() {
            @Override
            public String level() {
                return CallBodyPolicy.ALL;
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminController(
                        new AiLiteConfig(), catalog, (PromptManager) null, jdbc, null, null,
                        null, null, null, null, userPolicy))
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(AiLiteJson.mapper()))
                .build();

        // 读：必须是用户策略的真实档位，不能是 none；并标出不可写。
        mvc.perform(get("/ai-lite/admin/api/calls/body-level"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"level\":\"all\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"writable\":false")));

        // 写：必须说清为什么不能写，而不是笼统的「不可用」。
        mvc.perform(put("/ai-lite/admin/api/calls/body-level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"failure\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("只能读")));
    }

    /**
     * 有可写策略时：正常读写，并标记 writable=true。
     */
    @Test
    public void writablePolicyRoundTrips() throws Exception {
        JdbcTemplate jdbc = SqliteFiles.open("body-level-writable");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        jdbc.execute("CREATE TABLE AI_LITE_ADMIN_SETTING ("
                + "SETTING_KEY VARCHAR(64) PRIMARY KEY, SETTING_VALUE VARCHAR(32),"
                + "UPDATE_TIME TIMESTAMP)");
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);
        JdbcCallBodyPolicy policy = new JdbcCallBodyPolicy(jdbc, "AI_LITE_ADMIN_SETTING", 0);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminController(
                        new AiLiteConfig(), catalog, (PromptManager) null, jdbc, null,
                        policy, null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(AiLiteJson.mapper()))
                .build();

        mvc.perform(get("/ai-lite/admin/api/calls/body-level"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"writable\":true")));

        mvc.perform(put("/ai-lite/admin/api/calls/body-level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"all\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"level\":\"all\"")));
    }
}

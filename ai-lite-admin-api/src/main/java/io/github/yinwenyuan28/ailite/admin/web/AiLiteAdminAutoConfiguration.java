package io.github.yinwenyuan28.ailite.admin.web;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.admin.jdbc.CallBodySettings;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 管理 REST。只有开关打开、进程是 Servlet Web 应用、目录已经建好时才注册。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "ai-lite.admin", name = "enabled", havingValue = "true")
@AutoConfigureAfter(name = {
        "io.github.yinwenyuan28.ailite.admin.jdbc.AiLiteCatalogAutoConfiguration",
        "io.github.yinwenyuan28.ailite.autoconfigure.AiLiteAutoConfiguration",
        "io.github.yinwenyuan28.ailite.autoconfigure.AiLitePromptAutoConfiguration"
})
@EnableConfigurationProperties(AiLiteAdminProperties.class)
public class AiLiteAdminAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiLiteAdminAutoConfiguration.class);

    /**
     * @param config      配置文件里的供应商与业务 id
     * @param catalog     运行时目录；必须是 JDBC 实现
     * @param prompts     提示词管理器，没有则业务接口的提示词操作会说明原因
     * @param jdbc        调用记录查询
     * @param environment 读取调用记录表名
     * @param http        配置启用的 HTTP 传输；取不到时诊断退化为 HttpURLConnection
     * @param bodyPolicy  调用正文档位；取容器里已按 {@code ai-lite.catalog.setting-table}
     *                    配置好的 Bean，不要在这里自己 new
     * @return 管理 API 控制器
     */
    @Bean
    @ConditionalOnBean({RuntimeCatalog.class, AiLiteConfig.class, JdbcTemplate.class})
    public AdminController aiLiteAdminController(AiLiteConfig config, RuntimeCatalog catalog,
                                                 ObjectProvider<PromptManager> prompts,
                                                 JdbcTemplate jdbc, Environment environment,
                                                 ObjectProvider<HttpExecutor> http,
                                                 ObjectProvider<CallBodySettings> bodyPolicy) {
        if (!(catalog instanceof JdbcRuntimeCatalog)) {
            throw new IllegalStateException("管理 API 需要 JDBC 目录，请配置数据源并执行 ai-lite-catalog.sql");
        }
        // 用 ObjectProvider 而不是硬依赖：单独使用 admin-api（不带 Starter）时管理页仍然可用，
        // 但不能静默退化成另一条链路——那样"测试连通成功、业务调用失败"极难排查。
        HttpExecutor executor = http.getIfAvailable();
        if (executor == null) {
            log.warn("管理页未取到 HttpExecutor，测试连通/测试调用将使用 HttpURLConnection 兜底，"
                    + "与 ai-lite.http.transport 配置的传输不是同一条链路");
        }
        // 档位策略必须取容器里的 Bean：它带着 ai-lite.catalog.setting-table 配置。
        // 这里曾直接 new JdbcCallBodyPolicy(jdbc)，导致自定义表名对管理页无效——
        // 目录装配读的是配置表名、管理页读的是硬编码表名，两边结论不一致。
        CallBodySettings policy = bodyPolicy.getIfAvailable();
        if (policy == null) {
            log.warn("管理页未取到 CallBodySettings，调用正文档位不可读写");
        }
        return new AdminController(config, (JdbcRuntimeCatalog) catalog, prompts.getIfAvailable(), jdbc,
                environment.getProperty("ai-lite.store.jdbc.table-name"),
                policy, executor);
    }
}

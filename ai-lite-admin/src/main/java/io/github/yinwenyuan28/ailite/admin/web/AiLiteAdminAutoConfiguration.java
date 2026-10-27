package io.github.yinwenyuan28.ailite.admin.web;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcCallBodyPolicy;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
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
 * 管理页。只有开关打开、进程是 Servlet Web 应用、目录已经建好时才注册。
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

    /**
     * @param config      配置文件里的供应商与业务 id
     * @param catalog     运行时目录；必须是 JDBC 实现
     * @param prompts     提示词管理器，没有则业务页的提示词操作会说明原因
     * @param jdbc        调用记录查询
     * @param environment 读取调用记录表名
     * @return 管理页控制器
     */
    @Bean
    @ConditionalOnBean({RuntimeCatalog.class, AiLiteConfig.class, JdbcTemplate.class})
    public AdminController aiLiteAdminController(AiLiteConfig config, RuntimeCatalog catalog,
                                                 ObjectProvider<PromptManager> prompts,
                                                 JdbcTemplate jdbc, Environment environment,
                                                 AiLiteAdminProperties properties) {
        if (!(catalog instanceof JdbcRuntimeCatalog)) {
            throw new IllegalStateException("管理页需要 JDBC 目录，请配置数据源并执行 ai-lite-catalog.sql");
        }
        return new AdminController(config, (JdbcRuntimeCatalog) catalog, prompts.getIfAvailable(), jdbc,
                environment.getProperty("ai-lite.store.jdbc.table-name"),
                properties.isPageEnabled(), new JdbcCallBodyPolicy(jdbc));
    }
}

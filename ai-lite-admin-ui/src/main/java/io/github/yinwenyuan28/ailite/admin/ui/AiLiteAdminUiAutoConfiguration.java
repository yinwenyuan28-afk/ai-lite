package io.github.yinwenyuan28.ailite.admin.ui;

import io.github.yinwenyuan28.ailite.admin.web.AdminController;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 自带管理页。需引入本模块、打开 {@code ai-lite.admin.enabled} 与 {@code page-enabled}。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(AdminController.class)
@ConditionalOnProperty(prefix = "ai-lite.admin", name = "page-enabled", havingValue = "true")
@AutoConfigureAfter(name = "io.github.yinwenyuan28.ailite.admin.web.AiLiteAdminAutoConfiguration")
@EnableConfigurationProperties(AiLiteAdminUiProperties.class)
public class AiLiteAdminUiAutoConfiguration {

    /**
     * @return 管理页静态资源控制器
     */
    @Bean
    public AdminPageController aiLiteAdminPageController() {
        return new AdminPageController();
    }
}

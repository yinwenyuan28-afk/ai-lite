package io.github.yinwenyuan28.ailite.admin.web;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 调用方异步支持体检的自动装配。
 *
 * <p>与 {@link AiLiteAdminAutoConfiguration} 分开，因为两者的开关语义不同：
 * 管理页需要 {@code ai-lite.admin.enabled=true} 且要求数据源和目录齐备；
 * 体检只要求「进程是 Servlet Web 应用」，不需要数据库、不需要开管理页。
 * 把体检塞进管理页装配里，会让「没开管理页」的应用漏掉这项检查。</p>
 *
 * <p>依赖 {@code spring-webmvc}（本模块已有），因此不会把 Servlet 依赖强加给
 * 非 Web 应用——{@code @ConditionalOnWebApplication(SERVLET)} 会先把它挡掉。</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "ai-lite.admin.async-check", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AiLiteAsyncCheckProperties.class)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
public class AiLiteAsyncCheckAutoConfiguration {

    /**
     * 注册体检器。
     *
     * <p>没有加 {@code ai-lite.enabled} 开关：体检只读过滤器注册信息、只写日志，
     * 关掉 ai-lite 主功能时它也无害。真正的开关是
     * {@code ai-lite.admin.async-check.enabled}。</p>
     *
     * @param beanFactory 用于遍历过滤器注册
     * @param properties  体检开关
     * @return 体检器
     */
    @Bean
    @ConditionalOnMissingBean(AiLiteAsyncSupportChecker.class)
    public AiLiteAsyncSupportChecker aiLiteAsyncSupportChecker(
            ListableBeanFactory beanFactory, AiLiteAsyncCheckProperties properties) {
        return new AiLiteAsyncSupportChecker(beanFactory, properties);
    }
}

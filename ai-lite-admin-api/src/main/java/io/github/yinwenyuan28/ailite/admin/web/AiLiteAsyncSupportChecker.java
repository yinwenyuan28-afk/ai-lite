package io.github.yinwenyuan28.ailite.admin.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import javax.annotation.PostConstruct;
import javax.servlet.annotation.WebFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用方（宿主应用）的异步支持体检。
 *
 * <p><b>为什么 ai-lite 要主动查调用方。</b>ai-lite 提供的异步能力（{@code oneAsync}、
 * {@code *Async}）最终常被调用方直接作为 Controller 返回值，此时 Spring MVC 会走
 * Servlet 异步机制。Servlet 规范要求「servlet 和参与异步处理的每一个过滤器」都支持
 * 异步——只要有一环不支持，{@code startAsync()} 就抛
 * {@code Async support must be enabled on a servlet and for all filters involved in
 * async request processing}。</p>
 *
 * <p>这个错误最坑的地方是<b>业务其实跑成功了</b>：响应在过滤器处当场判失败，
 * 但异步任务仍在后台跑完，结果无处回填。现象是「接口 200 但前端拿不到数据，
 * 日志里却有业务成功的记录」，极易被误判成业务 bug。</p>
 *
 * <p>ai-lite 自己是干净的（流式走回调、不碰 Servlet 异步），但调用方不一定。
 * 在这里做一次启动期体检，能把这颗雷从「上线后才发现」提前到「启动时就看见」。</p>
 *
 * <p><b>检测范围说明。</b>遍历容器里所有 {@code FilterRegistrationBean}——
 * 用 {@code @WebFilter} 注册的过滤器也会被 Spring Boot 的
 * {@code WebFilterHandler} 转成 {@code FilterRegistrationBean}，所以这一条
 * 遍历能同时覆盖两种注册方式。这也意味着<b>依赖 jar 带进来的过滤器同样会被查到</b>,
 * 而靠 grep 源码是搜不到它们的。</p>
 *
 * <p><b>只告警不阻断。</b>过滤器链往往含大量第三方组件，某些过滤器与异步无关、
 * 关掉异步是有意设计；直接让应用起不来属于误伤。把类名明确打出来，定位成本已足够低。</p>
 */
public class AiLiteAsyncSupportChecker {

    private static final Logger log = LoggerFactory.getLogger(AiLiteAsyncSupportChecker.class);

    private final ListableBeanFactory beanFactory;
    private final AiLiteAsyncCheckProperties properties;

    /**
     * @param beanFactory 用于取容器里的过滤器注册 Bean
     * @param properties  体检开关
     */
    public AiLiteAsyncSupportChecker(ListableBeanFactory beanFactory,
                                     AiLiteAsyncCheckProperties properties) {
        this.beanFactory = beanFactory;
        this.properties = properties;
    }

    /**
     * 启动期执行一次体检。
     */
    @PostConstruct
    public void check() {
        if (!properties.isEnabled()) {
            return;
        }
        List<String> unsafe = check(properties.isVerbose());
        if (unsafe.isEmpty()) {
            log.info("ai-lite 异步支持体检通过：调用方过滤器链均支持 async");
            return;
        }
        for (String name : unsafe) {
            log.error("ai-lite 异步支持体检：过滤器 {} 未开启 asyncSupported。"
                    + "若接口返回 CompletableFuture/SseEmitter，会在该过滤器处失败，"
                    + "表现为「响应失败但业务已成功」。"
                    + "修复：@WebFilter 加 asyncSupported = true；"
                    + "FilterRegistrationBean 调 setAsyncSupported(true)；"
                    + "依赖 jar 里的过滤器用 BeanPostProcessor 补开。"
                    + "详见 docs/guide-异步支持排查.md", name);
        }
    }

    /**
     * 执行体检并返回有问题的过滤器类名。
     *
     * <p>抽成公开方法是为了能被单测直接断言——只靠日志无法验证体检是否真的有区分力。</p>
     *
     * @param verbose true 时把全部过滤器也打出来（排查用）
     * @return 不支持异步的过滤器类名；全部正常时返回空列表
     */
    public List<String> check(boolean verbose) {
        List<String> unsafe = new ArrayList<String>();
        String[] beanNames = beanFactory.getBeanNamesForType(FilterRegistrationBean.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = beanFactory.getBean(beanName);
            } catch (RuntimeException ex) {
                // 个别注册 Bean 初始化失败不该影响体检本身
                log.debug("跳过无法实例化的过滤器注册 {}：{}", beanName, ex.getMessage());
                continue;
            }
            if (!(bean instanceof FilterRegistrationBean)) {
                continue;
            }
            FilterRegistrationBean<?> registration = (FilterRegistrationBean<?>) bean;
            Object filter = registration.getFilter();
            if (filter == null) {
                continue;
            }
            String className = filter.getClass().getName();
            boolean async = registration.isAsyncSupported();
            if (verbose) {
                String origin = filter.getClass().getAnnotation(WebFilter.class) != null
                        ? "@WebFilter" : "FilterRegistrationBean";
                log.info("ai-lite 异步支持体检：{} async={} 来源={} order={}",
                        className, async, origin, registration.getOrder());
            }
            if (!async) {
                unsafe.add(className);
            }
        }
        return unsafe;
    }

    /**
     * @return 体检开关，便于调用方读取当前配置
     */
    public AiLiteAsyncCheckProperties getProperties() {
        return properties;
    }
}

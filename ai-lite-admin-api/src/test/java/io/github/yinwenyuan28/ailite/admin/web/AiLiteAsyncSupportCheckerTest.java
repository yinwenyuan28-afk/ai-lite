package io.github.yinwenyuan28.ailite.admin.web;

import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用方异步支持体检的区分力测试。
 *
 * <p>要证明的是「能挑出真正有问题的那个」，而不只是「方法不抛异常」。
 * 因此同时验证：不支持异步的会被报出、支持异步的不会误报、
 * 多个过滤器时只报有问题的那些。</p>
 */
public class AiLiteAsyncSupportCheckerTest {

    @Test
    public void reportsFilterWithoutAsyncSupport() {
        FilterRegistrationBean<PlainFilter> bean = registration(new PlainFilter());
        bean.setAsyncSupported(false);

        List<String> unsafe = checker(bean).check(false);

        assertThat(unsafe).containsExactly(PlainFilter.class.getName());
    }

    @Test
    public void doesNotReportFilterWithAsyncSupport() {
        FilterRegistrationBean<PlainFilter> bean = registration(new PlainFilter());
        bean.setAsyncSupported(true);

        assertThat(checker(bean).check(false)).isEmpty();
    }

    @Test
    public void reportsOnlyTheBrokenOneAmongMany() {
        FilterRegistrationBean<PlainFilter> good = registration(new PlainFilter());
        good.setAsyncSupported(true);
        FilterRegistrationBean<BrokenFilter> broken = registration(new BrokenFilter());
        broken.setAsyncSupported(false);
        FilterRegistrationBean<AnotherFilter> another = registration(new AnotherFilter());
        another.setAsyncSupported(true);

        List<String> unsafe = checker(good, broken, another).check(false);

        // 关键：不能因为有一个坏的就把全部报出来，也不能漏掉
        assertThat(unsafe).containsExactly(BrokenFilter.class.getName());
    }

    @Test
    public void ignoresRegistrationWithoutFilterInstance() {
        FilterRegistrationBean<PlainFilter> bean = new FilterRegistrationBean<PlainFilter>();

        assertThat(checker(bean).check(false)).isEmpty();
    }

    @Test
    public void disabledSwitchSkipsCheck() {
        AiLiteAsyncCheckProperties properties = new AiLiteAsyncCheckProperties();
        properties.setEnabled(false);
        FilterRegistrationBean<PlainFilter> bean = registration(new PlainFilter());
        bean.setAsyncSupported(false);

        // 关闭开关时 check() 直接返回，但显式调用 check(verbose) 仍应能查到问题，
        // 便于运维手动触发诊断
        AiLiteAsyncSupportChecker checker = new AiLiteAsyncSupportChecker(
                beanFactory(bean), properties);
        checker.check();

        assertThat(checker.check(false)).containsExactly(PlainFilter.class.getName());
    }

    private static AiLiteAsyncSupportChecker checker(FilterRegistrationBean<?>... beans) {
        AiLiteAsyncCheckProperties properties = new AiLiteAsyncCheckProperties();
        properties.setEnabled(true);
        return new AiLiteAsyncSupportChecker(beanFactory(beans), properties);
    }

    /**
     * 把待测过滤器注册进一个最小 BeanFactory。
     *
     * @param beans 注册 Bean
     * @return BeanFactory
     */
    private static DefaultListableBeanFactory beanFactory(FilterRegistrationBean<?>... beans) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < beans.length; i++) {
            factory.registerSingleton("filter" + i, beans[i]);
        }
        return factory;
    }

    private static <T extends OncePerRequestFilter> FilterRegistrationBean<T> registration(T filter) {
        return new FilterRegistrationBean<T>(filter);
    }

    /** 用于验证不会误报的普通过滤器。 */
    static class PlainFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }

    /** 模拟带进来的第三方过滤器。 */
    static class BrokenFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }

    /** 另一个正常过滤器，用于验证多过滤器场景。 */
    static class AnotherFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }
}

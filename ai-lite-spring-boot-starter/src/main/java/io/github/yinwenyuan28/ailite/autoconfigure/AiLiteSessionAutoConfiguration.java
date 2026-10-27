package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.session.AgentSessionLoop;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.InMemoryAgentSessionStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话层自动装配。默认使用有界内存存储；引入 Redis/JDBC 模块后可自行覆盖。
 */
@Configuration
@AutoConfigureAfter(AiLiteAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiLiteSessionAutoConfiguration implements BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-lite.session", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentSessionStore aiLiteSessionStore(AiLiteConfig config) {
        return new InMemoryAgentSessionStore(config.getSession().getMaxEntries(),
                config.getSession().getTtlSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-lite.session", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentSessionLoop aiLiteSessionLoop(AgentLoop agentLoop, AgentSessionStore sessionStore,
                                             ExecutorProvider executor) {
        // 只有传入上下文配置才会启用预算检查与自动压缩；不传则行为与旧版完全一致。
        //
        // 配置从 BeanFactory 取而不是加方法参数：@Bean 方法是公开签名，
        // 加参数会被 japicmp 判成"方法被删"（破坏 0.1.7 的二进制兼容）。
        AiLiteConfig.Context context = null;
        try {
            context = beanFactory.getBean(AiLiteConfig.class).getContext();
        } catch (BeansException ex) {
            // 配置缺席时退化为"无预算检查、无压缩"，与旧版行为一致。
        }
        return new AgentSessionLoop(agentLoop, sessionStore, executor, context);
    }
}

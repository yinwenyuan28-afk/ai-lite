package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.session.AgentSessionLoop;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.InMemoryAgentSessionStore;
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
public class AiLiteSessionAutoConfiguration {

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
        return new AgentSessionLoop(agentLoop, sessionStore, executor);
    }
}

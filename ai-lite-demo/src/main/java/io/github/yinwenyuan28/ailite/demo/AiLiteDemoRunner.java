package io.github.yinwenyuan28.ailite.demo;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 演示入口：打印已配置的供应商，并在 {@code ai-lite.demo.run=true} 时发一次真实请求。
 */
@Component
public class AiLiteDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiLiteDemoRunner.class);

    private final LlmGateway gateway;
    private final AiLiteConfig config;
    private final boolean run;
    private final String prompt;

    public AiLiteDemoRunner(LlmGateway gateway, AiLiteConfig config,
                            @Value("${ai-lite.demo.run:false}") boolean run,
                            @Value("${ai-lite.demo.prompt:你好}") String prompt) {
        this.gateway = gateway;
        this.config = config;
        this.run = run;
        this.prompt = prompt;
    }

    @Override
    public void run(String... args) {
        Map<String, AiLiteConfig.Provider> providers = config.getLlm().getProviders();
        log.info("AI Lite demo started, providers={}, profiles={}",
                providers.keySet(), config.getLlm().getProfiles().keySet());
        if (!run) {
            log.info("ai-lite.demo.run=false，跳过真实请求");
            return;
        }
        LlmResult result = gateway.one(LlmCall.biz("demo-chat",
                Collections.singletonList(LlmMessage.user(prompt))));
        log.info("回答：{}", result.getContent());
        log.info("模型={} 停止原因={} 用量={}",
                result.getModelId(), result.getStopReason(), result.getUsage());
    }
}

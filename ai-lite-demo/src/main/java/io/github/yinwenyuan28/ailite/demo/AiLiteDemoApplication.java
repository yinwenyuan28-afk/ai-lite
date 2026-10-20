package io.github.yinwenyuan28.ailite.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示入口。M0 阶段只验证工程可启动；M6 会补上一次真实调用、流式调用和工具循环的示例。
 */
@SpringBootApplication
public class AiLiteDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiLiteDemoApplication.class, args);
    }
}

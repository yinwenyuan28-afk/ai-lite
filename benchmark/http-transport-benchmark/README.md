# HTTP 传输并发基准

对比 `okhttp`、`okhttp-virtual`、`jetty`、`webclient`、`ktor-cio`、`jdk8` 六种传输。
基准只测 **HTTP 传输层**：fake LLM 服务返回固定 JSON 或 SSE，不经过协议适配器和网关，
避免 JSON 解析、调用记录等逻辑干扰线程模型对比。

## 1. 方法

- 本地 fake LLM 服务：Jetty Server，非流式延迟 30ms；流式 8 个 SSE chunk，块间 2ms。
- 每个场景用 `concurrency` 条独立链，每条链串行发请求，因此在途请求数恰好等于并发度。
- 每个 transport 只创建一次执行器，跨场景复用连接池。
- 每场景 1000 个请求；100 并发 = 10 轮，500 = 2 轮，1000 = 1 轮。
- 指标：吞吐、p50/p95/p99、平台线程峰值、transport 线程峰值、堆使用。
- `peak threads` 包含 fake server（Jetty Server 1024 线程池）和 JVM 线程；`transport threads`
  只统计客户端线程名前缀，尽量隔离服务端影响。
- 虚拟线程不出现在 `Thread.getAllStackTraces()`，所以 `okhttp-virtual` 的
  `transport threads` 显示 0，这是预期行为，不代表没有线程。

## 2. 环境

- Windows 10 x64 开发机，非受控基准机。
- JDK 8u292：`okhttp`、`jetty`、`webclient`、`ktor-cio`、`jdk8`。
- JDK 25.0.3：`okhttp-virtual`。
- fake server：Jetty 9.4.22，1024 线程池。

结果用于**横向相对比较**，不能直接当作生产容量承诺。

## 3. 结果

| transport | mode | concurrency | requests | ok | err | req/s | p50 ms | p95 ms | p99 ms | peak threads | transport threads | heap MB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| okhttp | nonstream | 100 | 1000 | 1000 | 0 | 3027.4 | 31.35 | 35.37 | 40.22 | 264 | 116 | 65 |
| okhttp | nonstream | 500 | 1000 | 1000 | 0 | 5444.4 | 54.99 | 102.92 | 113.55 | 1008 | 511 | 101 |
| okhttp | nonstream | 1000 | 1000 | 1000 | 0 | 7237.5 | 69.38 | 79.62 | 94.55 | 1481 | 754 | 117 |
| okhttp | stream | 100 | 1000 | 1000 | 0 | 582.1 | 170.30 | 187.59 | 189.56 | 1499 | 757 | 95 |
| okhttp | stream | 500 | 1000 | 1000 | 0 | 2593.8 | 177.35 | 202.01 | 205.43 | 1499 | 757 | 153 |
| okhttp | stream | 1000 | 1000 | 1000 | 0 | 2469.0 | 318.15 | 340.56 | 358.01 | 2054 | 1007 | 143 |
| okhttp-virtual | nonstream | 100 | 1000 | 1000 | 0 | 2878.8 | 32.07 | 40.02 | 44.09 | 168 | 0 | 61 |
| okhttp-virtual | nonstream | 500 | 1000 | 1000 | 0 | 6192.5 | 45.19 | 88.34 | 94.06 | 551 | 0 | 85 |
| okhttp-virtual | nonstream | 1000 | 1000 | 1000 | 0 | 6896.7 | 68.60 | 103.53 | 107.77 | 907 | 0 | 136 |
| okhttp-virtual | stream | 100 | 1000 | 1000 | 0 | 590.3 | 168.51 | 175.83 | 183.25 | 907 | 0 | 158 |
| okhttp-virtual | stream | 500 | 1000 | 1000 | 0 | 2720.9 | 173.77 | 190.33 | 196.05 | 907 | 0 | 135 |
| okhttp-virtual | stream | 1000 | 1000 | 1000 | 0 | 3014.8 | 239.85 | 252.63 | 257.09 | 1081 | 0 | 161 |
| jetty | nonstream | 100 | 1000 | 1000 | 0 | 2912.6 | 30.87 | 35.26 | 44.45 | 271 | 123 | 90 |
| jetty | nonstream | 500 | 1000 | 1000 | 0 | 5967.6 | 42.17 | 80.44 | 93.62 | 656 | 201 | 120 |
| jetty | nonstream | 1000 | 1000 | 1000 | 0 | 10182.2 | 36.30 | 67.54 | 69.29 | 852 | 201 | 159 |
| jetty | stream | 100 | 1000 | 1000 | 0 | 660.6 | 156.59 | 171.97 | 175.07 | 852 | 201 | 196 |
| jetty | stream | 500 | 1000 | 1000 | 0 | 2751.2 | 169.21 | 176.12 | 182.62 | 851 | 201 | 133 |
| jetty | stream | 1000 | 1000 | 1000 | 0 | 3832.7 | 170.71 | 184.85 | 189.95 | 1242 | 201 | 213 |
| webclient | nonstream | 100 | 1000 | 1000 | 0 | 1878.6 | 46.47 | 52.21 | 57.67 | 168 | 16 | 56 |
| webclient | nonstream | 500 | 1000 | 1000 | 0 | 3792.0 | 45.65 | 68.66 | 82.49 | 338 | 16 | 137 |
| webclient | nonstream | 1000 | 1000 | 1000 | 0 | 4929.3 | 40.05 | 47.59 | 51.48 | 380 | 16 | 142 |
| webclient | stream | 100 | 1000 | 1000 | 0 | 585.3 | 169.60 | 176.71 | 180.53 | 380 | 16 | 91 |
| webclient | stream | 500 | 1000 | 1000 | 0 | 2391.5 | 170.34 | 188.53 | 208.30 | 569 | 16 | 184 |
| webclient | stream | 1000 | 1000 | 1000 | 0 | 2492.5 | 191.46 | 231.71 | 251.10 | 904 | 16 | 206 |
| ktor-cio | nonstream | 100 | 1000 | 1000 | 0 | 1574.8 | 55.07 | 89.65 | 112.48 | 257 | 81 | 62 |
| ktor-cio | nonstream | 500 | 1000 | 1000 | 0 | 1387.2 | 96.04 | 639.42 | 655.77 | 480 | 130 | 226 |
| ktor-cio | nonstream | 1000 | 1000 | 1000 | 0 | 1328.4 | 670.76 | 732.80 | 749.03 | 659 | 130 | 219 |
| ktor-cio | stream | 100 | 1000 | 1000 | 0 | 543.2 | 172.76 | 204.80 | 209.68 | 659 | 130 | 363 |
| ktor-cio | stream | 500 | 1000 | 1000 | 0 | 1069.8 | 212.73 | 739.36 | 742.57 | 658 | 130 | 142 |
| ktor-cio | stream | 1000 | 1000 | 1000 | 0 | 1151.4 | 776.07 | 832.16 | 845.88 | 964 | 130 | 326 |
| jdk8 | nonstream | 100 | 1000 | 1000 | 0 | 2898.3 | 31.29 | 44.90 | 49.11 | 252 | 105 | 69 |
| jdk8 | nonstream | 500 | 1000 | 1000 | 0 | 1396.0 | 84.60 | 645.09 | 649.53 | 918 | 504 | 137 |
| jdk8 | nonstream | 1000 | 1000 | 1000 | 0 | 581.2 | 1126.47 | 1657.59 | 1663.10 | 1403 | 990 | 97 |
| jdk8 | stream | 100 | 1000 | 1000 | 0 | 495.3 | 170.11 | 336.78 | 353.49 | 1403 | 990 | 217 |
| jdk8 | stream | 500 | 1000 | 1000 | 0 | 1020.6 | 199.71 | 743.02 | 753.35 | 1402 | 990 | 148 |
| jdk8 | stream | 1000 | 1000 | 1000 | 0 | 815.0 | 819.81 | 1142.31 | 1210.89 | 1445 | 1000 | 100 |

> JDK8 的 `stream/1000` 是在独立 JVM 中单独跑的；同一 JVM 连续跑完全部场景会累积
> 大量 TIME_WAIT 端口，出现 `BindException`。这是 JDK8 阻塞实现的连接复用能力，
> 不是传输逻辑错误。其他行也建议按 transport 分开进程复现。

## 4. 结论

- **Jetty 是 JDK 8 下的高并发主路径**：1000 并发非流式 10.2k req/s，流式 3.8k req/s，
  客户端线程稳定在约 201，不随并发线性增长。这是 NIO selector + 连接池的直接收益。
- **`okhttp-virtual` 是 JDK 21+ 改动最小的方案**：非流式 6.9k、流式 3.0k，接近 Jetty；
  虚拟线程不计入平台线程统计，平台线程峰值主要来自 fake server。
- **OkHttp 平台线程版**：非流式吞吐不低（7.2k），但 1000 并发时约 754–1007 个
  Dispatcher 线程；流式长连接会持续占线程，这是它和 NIO 的核心区别。
- **WebClient**：16 个 event loop 线程，非流式 4.9k、流式 2.5k；线程效率好，
  但需要 WebFlux/Reactor Netty 依赖，Boot 2.1 还有 RN 0.8 重复 POST 风险。
- **Ktor CIO**：约 1.3k/1.15k，130 个 IO/selector 线程；高并发下 p95 明显变长，
  且 Ktor 2.x 已 EOL，只建议 Kotlin 项目按需使用。
- **JDK8**：非流式 1000 并发只有 581 req/s，约 990 个平台线程；它是零依赖兜底，
  不适合高并发，符合“每请求一个阻塞线程”的模型。

## 5. 复现

```bash
# 仓库根目录
mvn install -DskipTests

cd benchmark/http-transport-benchmark
mvn -q exec:java -Dexec.args="--transports=okhttp --concurrency=100,500,1000 --requests=1000 --delay-ms=30 --stream-chunks=8 --stream-delay-ms=2 --server-threads=1024"
mvn -q exec:java -Dexec.args="--transports=jetty ..."
mvn -q exec:java -Dexec.args="--transports=webclient ..."
mvn -q exec:java -Dexec.args="--transports=ktor-cio ..."
mvn -q exec:java -Dexec.args="--transports=jdk8 ..."
```

JDK 21+ 增加 `okhttp-virtual`：

```bash
mvn -q exec:java -Dexec.args="--transports=okhttp-virtual ..."
```

可用参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--transports` | 全部 | 逗号分隔 |
| `--modes` | `nonstream,stream` | 逗号分隔 |
| `--concurrency` | `100,500,1000` | 逗号分隔 |
| `--requests` | 2000 | 每场景请求数（会向上取整到 `concurrency` 的倍数） |
| `--delay-ms` | 50 | 非流式首包延迟；流式首 chunk 延迟 |
| `--stream-chunks` | 8 | SSE chunk 数 |
| `--stream-delay-ms` | 5 | SSE chunk 间隔 |
| `--server-threads` | 256 | fake server 线程池 |

## 6. 基准过程中发现并修复的问题

1. **JDK8 每次请求 `disconnect()`**：关闭底层 socket，keep-alive 完全失效，高并发下
   出现端口耗尽。改为正常完成只关闭流，取消/异常才 disconnect。
2. **OkHttp 默认只保留 5 个空闲连接**：调大 `dispatcher.max-requests` 后连接池没跟上，
   高并发反复建连。连接池大小现在与 `max-requests` 对齐。
3. **JDK `HttpServer` 作为 fake server 不稳定**：高并发下主动断连，产生大量
   `EOFException`。基准改用 Jetty Server，keep-alive 和大并发更稳定。

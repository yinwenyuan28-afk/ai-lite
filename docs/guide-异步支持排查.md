# 异步支持排查与修复

> 适用场景：把 ai-lite 的异步结果（`CompletableFuture` / `SseEmitter`）直接作为 Spring MVC 接口返回值。
> 相关配置：`ai-lite.admin.async-check.enabled`、`ai-lite.admin.async-check.verbose`

---

## 1. 症状

这个错误极其隐蔽，认准三个同时出现的特征：

1. **接口报错，或返回 200 但前端拿不到数据**
2. **后台日志里能看到业务已经成功执行完**（有结果、有耗时）
3. 日志里可能有：`Async support must be enabled on a servlet and for all filters involved in async request processing`

「成功」和「失败」同时为真，是理解整件事的钥匙。

### 真实案例

```
17:29:44 ERROR  Async support must be enabled on a servlet and for all filters involved in async request processing
17:29:47 INFO   智能优化完成 端点=text-optimize 原文5字 结果133字 耗时2703ms
```

先报错、后成功、隔了 3 秒。前端收到的是错误包，所以优化结果没回填；但业务确实跑完了。

---

## 2. 为什么会「成功又失败」

Controller 返回 `CompletableFuture` 或 `SseEmitter` 时，Spring MVC 走 Servlet 异步机制：

```
① 请求进入 → request.startAsync()        ← 在某个过滤器上被拒绝，响应当场判失败
② CompletableFuture 任务在后台线程池继续跑
③ 3 秒后拿到结果                          ← 但响应已经失败了，结果无处回填
④ 前端收到错误包
```

**业务成功不等于响应成功**。日志里的「完成」是 ②③ 的记录，前端看到的是 ① 的结果。

### 根本原因

Servlet 规范要求：

> 异步请求要求 **servlet 和参与异步处理的每一个 filter** 都声明支持异步。

这是**全链路的与运算**，不是或运算。九个过滤器里八个支持、一个不支持，整个异步机制就不可用。

---

## 3. 排查

### 3.1 用内置体检（推荐）

引入 `ai-lite-admin-api` 后，启动时会自动体检，日志形如：

```
ai-lite 异步支持体检通过：调用方过滤器链均支持 async
```

有问题时：

```
ai-lite 异步支持体检：过滤器 com.example.LegacyFilter 未开启 asyncSupported。
若接口返回 CompletableFuture/SseEmitter，会在该过滤器处失败，表现为「响应失败但业务已成功」。
修复：@WebFilter 加 asyncSupported = true；...
```

想看全部过滤器的状态（排查时打开）：

```yaml
ai-lite:
  admin:
    async-check:
      verbose: true      # 打印每个过滤器的 async 状态与来源
```

输出：

```
ai-lite 异步支持体检：com.example.FastFilter    async=true  来源=FilterRegistrationBean order=1
ai-lite 异步支持体检：com.example.LegacyFilter  async=false 来源=@WebFilter              order=2147483647
```

关闭体检：

```yaml
ai-lite:
  admin:
    async-check:
      enabled: false
```

### 3.2 手动写一个

如果没引 admin-api，自己写也行：

```java
@Slf4j
@Component
public class AsyncSupportSelfCheck {

    private final ApplicationContext ctx;

    public AsyncSupportSelfCheck(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @PostConstruct
    public void check() {
        for (FilterRegistrationBean<?> bean
                : ctx.getBeansOfType(FilterRegistrationBean.class).values()) {
            String name = bean.getFilter() == null
                    ? "unknown" : bean.getFilter().getClass().getName();
            if (!bean.isAsyncSupported()) {
                log.error("过滤器 {} 未开启 asyncSupported", name);
            }
        }
    }
}
```

### 3.3 为什么遍历 FilterRegistrationBean 就够了

用 `@WebFilter` 注册的过滤器，Spring Boot 的 `WebFilterHandler` 会把它**转成 `FilterRegistrationBean`**：

```java
// WebFilterHandler.doHandle 的核心逻辑
BeanDefinitionBuilder.rootBeanDefinition(FilterRegistrationBean.class)
        .addPropertyValue("asyncSupported", attributes.get("asyncSupported"))
        .addPropertyValue("filter", beanDefinition)
        // ...
```

所以这一条遍历能同时覆盖两种注册方式。

**关键推论：不要只 grep 项目源码。** 依赖 jar 带进来的 `@WebFilter` 同样会被扫到并被转成 `FilterRegistrationBean`，但源码搜索完全找不到它们——这正是这类问题最常见的盲区。

### 3.4 一个常见误判

不要靠「注解默认值」推断，要实测。两个容易搞反的点：

| 注册方式 | `asyncSupported` 默认值 |
| --- | --- |
| `FilterRegistrationBean` | **true**（`DynamicRegistrationBean` 构造器初值为 true） |
| `@WebFilter` 注解 | **false** |

核对方法：

```bash
# 看注解默认值（0 = false）
javap -v javax.servlet.annotation.WebFilter | grep -A3 asyncSupported

# 看 Spring Boot 的默认值（iconst_1 = true）
javap -c -p org.springframework.boot.web.servlet.DynamicRegistrationBean | grep -A3 putfield
```

---

## 4. 修复

按过滤器所在位置分三种情况。

### 4.1 在项目源码里，用 `@WebFilter`

```java
@WebFilter(urlPatterns = "/*", asyncSupported = true)   // 默认 false，必须显式写
public class MyFilter extends OncePerRequestFilter { ... }
```

### 4.2 在项目源码里，用 `FilterRegistrationBean`

```java
@Bean
public FilterRegistrationBean<MyFilter> myFilter() {
    FilterRegistrationBean<MyFilter> bean = new FilterRegistrationBean<>(new MyFilter());
    bean.setAsyncSupported(true);   // Spring Boot 2.x 默认已是 true，显式写只为明确意图
    return bean;
}
```

### 4.3 在依赖 jar 里，改不了源码

用 `BeanPostProcessor` 在实例化之后改标志位：

```java
@Configuration
public class AsyncFilterPatch {

    private static final Set<String> TARGETS =
            Collections.singleton("com.example.LegacyFilter");

    @Bean
    public static BeanPostProcessor forceAsyncSupported() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof FilterRegistrationBean)) {
                    return bean;
                }
                FilterRegistrationBean<?> reg = (FilterRegistrationBean<?>) bean;
                if (reg.getFilter() != null
                        && TARGETS.contains(reg.getFilter().getClass().getName())) {
                    reg.setAsyncSupported(true);
                }
                return bean;
            }
        };
    }
}
```

---

## 5. 为什么必须是 BeanPostProcessor

这是整件事最关键、也最容易做错的地方。

### 5.1 完整生命周期链路

```
① BeanFactoryPostProcessor（ServletComponentRegisteringPostProcessor）
   扫描 @WebFilter → 注册 FilterRegistrationBean 的 BeanDefinition
        ↓                        ← 定义同名 Bean 会被这一步覆盖
② Bean 实例化
   FilterRegistrationBean 对象被创建
        ↓
③ BeanPostProcessor             ← 【选这里】实例刚建好，字段可改
   setAsyncSupported(true)
        ↓
④ ServletContextInitializerBeans 收集所有 FilterRegistrationBean
        ↓
⑤ 注册进 Servlet 容器（asyncSupported 在此定型）
```

`BeanPostProcessor` 卡在 ③，早于 ④⑤ 的收集与注册。这个位置改的字段，才会真正影响容器里的 filter 配置。

### 5.2 为什么定义同名 Bean 不行

直觉是「注册一个同名 Bean 把它顶掉」。但 Spring 里同名 Bean 是否生效，取决于**谁注册得更晚**：

```
BeanFactoryPostProcessor（扫描 @WebFilter，注册 BeanDefinition）  ← 更晚
        ↑
@Bean 方法（注册 BeanDefinition）                                  ← 更早
```

后者被前者**反向覆盖**，达不到目的。

### 5.3 一句话记法

> 改「**定义**」要早（`BeanFactoryPostProcessor`），
> 改「**实例状态**」要晚但必须在定型之前（`BeanPostProcessor`）。

判断标准是：**你要改的东西在哪个阶段定型。**

### 5.4 两个实现细节

**`@Bean` 方法必须是 `static`。** `BeanPostProcessor` 需要早于普通 Bean 实例化；如果来自非静态方法，配置类本身得先被创建，会引出「BeanPostProcessor 未被所有 Bean 应用」的告警和潜在循环依赖。

**用 `postProcessAfterInitialization` 而不是 `Before`。** 这里改的是配置对象的字段，不是绕过初始化逻辑；`After` 更符合语义。

---

## 6. 安全提醒

**只对明确的类名白名单生效，不要无差别放开。**

有些过滤器关掉异步是有意设计。改动前先反编译看它的 `doFilter` 有没有真异步语义。

一个判断例子：

```java
// 反编译看到这样的实现，说明它没有异步语义，开启 async 不改变行为
public void doFilter(req, resp, chain) {
    if (enabled) { ...读请求头做去重... }
    chain.doFilter(req, resp);      // 默认直接放行
}
```

同时确认那个功能是否真的在用（比如配置项是否配了）。如果功能本来就没启用，改动的影响面几乎为零。

---

## 7. 为什么不阻断启动

体检只打 ERROR 日志，不让应用启动失败。

理由：过滤器链往往含大量第三方组件，某些过滤器与异步无关、关掉异步是合理的。直接让应用起不来属于**误伤**——而把类名明确打出来，定位成本已经降到很低。

如果你希望严格模式，可以在自检里改成抛异常：

```java
if (!unsafe.isEmpty()) {
    throw new IllegalStateException("过滤器链不支持 async：" + unsafe);
}
```

---

## 8. 与其他异步机制的区分

容易混淆的一点：**ai-lite 自身的流式不依赖这套机制**。

ai-lite 的流式走回调（`AgentStreamListener`），由调用方决定怎么输出。只有当你把结果通过 `SseEmitter` / `CompletableFuture` 交给 Spring MVC 时，才会踩 Servlet 异步的坑。

所以：

| 用法 | 是否受 asyncSupported 影响 |
| --- | --- |
| `gateway.oneAsync()` + 自己 `future.thenAccept()` | 不受影响 |
| `gateway.oneAsync()` 直接 `return future`（Controller 返回值） | **受影响** |
| `gateway.chatStream(..., listener)` 回调里推自定义通道 | 不受影响 |
| 回调里写 `SseEmitter`（Controller 返回它） | **受影响** |

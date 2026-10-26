package io.github.yinwenyuan28.ailite.session.redis;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionCodec;
import io.github.yinwenyuan28.ailite.session.AgentSessionLoop;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import io.github.yinwenyuan28.ailite.session.CachingAgentSessionStore;
import io.github.yinwenyuan28.ailite.session.InMemoryAgentSessionStore;
import io.github.yinwenyuan28.ailite.session.SessionCache;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 会话缓存集成测试。
 *
 * <p>默认连接 127.0.0.1:6379 的 DB 3。密码从 {@code -Dredis.password} 或
 * {@code REDIS_PASSWORD} 读取；Redis 不可用时整类跳过，避免 CI 红。</p>
 */
public class RedisSessionCacheTest {

    private static final String PREFIX = "ai-lite:test:redis:";
    private static final String PING_KEY = PREFIX + "__ping__";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static boolean available;

    private RedisSessionCache cache;

    @BeforeClass
    public static void connect() {
        String host = property("redis.host", "REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("redis.port", "REDIS_PORT", "6379"));
        int database = Integer.parseInt(property("redis.database", "REDIS_DATABASE", "3"));
        String password = property("redis.password", "REDIS_PASSWORD", "");

        factory = new LettuceConnectionFactory(host, port);
        if (!password.isEmpty()) {
            factory.setPassword(password);
        }
        factory.setDatabase(database);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        try {
            template.hasKey(PING_KEY);
            available = true;
        } catch (RuntimeException ex) {
            available = false;
        }
        Assume.assumeTrue("本机 Redis 不可用，跳过 Redis 集成测试", available);
    }

    @AfterClass
    public static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Before
    public void setUp() {
        cleanup();
        cache = new RedisSessionCache(template, PREFIX, true);
    }

    @After
    public void tearDown() {
        cleanup();
    }

    @Test
    public void usesDatabase3() {
        assertThat(factory.getDatabase()).isEqualTo(3);
    }

    @Test
    public void putAndGetRoundTrip() {
        AgentSession session = session("u1", "s1", 2L);
        session.setMessages(java.util.Arrays.asList(
                AgentSessionMessage.user("你好"),
                AgentSessionMessage.assistant("hi", "think", null)));

        cache.put(session, 60L);

        assertThat(template.hasKey(PREFIX + "u1:s1")).isTrue();
        Optional<AgentSession> loaded = cache.get("u1", "s1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getUserId()).isEqualTo("u1");
        assertThat(loaded.get().getSessionId()).isEqualTo("s1");
        assertThat(loaded.get().getBizId()).isEqualTo("biz");
        assertThat(loaded.get().getVersion()).isEqualTo(2L);
        assertThat(loaded.get().getMessages()).hasSize(2);
        assertThat(loaded.get().getMessages().get(0).getText()).isEqualTo("你好");
        assertThat(loaded.get().getMessages().get(1).getThinkingText()).isEqualTo("think");
    }

    @Test
    public void ttlExpiresEntry() throws Exception {
        cache.put(session("u1", "s1", 0L), 1L);
        assertThat(cache.get("u1", "s1")).isPresent();

        Thread.sleep(1500L);

        assertThat(cache.get("u1", "s1")).isEmpty();
        assertThat(template.hasKey(PREFIX + "u1:s1")).isFalse();
    }

    @Test
    public void deleteRemovesEntry() {
        cache.put(session("u1", "s1", 0L), 60L);

        cache.delete("u1", "s1");

        assertThat(cache.get("u1", "s1")).isEmpty();
        assertThat(template.hasKey(PREFIX + "u1:s1")).isFalse();
    }

    @Test
    public void userMismatchReturnsEmpty() {
        AgentSession other = session("other", "s1", 0L);
        template.opsForValue().set(PREFIX + "u1:s1", AgentSessionCodec.encodeSession(other));

        assertThat(cache.get("u1", "s1")).isEmpty();
    }

    @Test
    public void sessionIdMismatchReturnsEmpty() {
        AgentSession other = session("u1", "other", 0L);
        template.opsForValue().set(PREFIX + "u1:s1", AgentSessionCodec.encodeSession(other));

        assertThat(cache.get("u1", "s1")).isEmpty();
    }

    @Test
    public void corruptedJsonIsTreatedAsMissAndDeleted() {
        template.opsForValue().set(PREFIX + "u1:s1", "{oops");

        assertThat(cache.get("u1", "s1")).isEmpty();
        assertThat(template.hasKey(PREFIX + "u1:s1")).isFalse();
    }

    @Test
    public void blankKeysAreRejected() {
        assertThatThrownBy(() -> cache.get("", "s1"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("userId 与 sessionId 不能为空");
        assertThatThrownBy(() -> cache.put(new AgentSession(), 60L))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    public void unicodeRoundTrip() {
        AgentSession session = session("用户", "会话", 0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("你好😀")));

        cache.put(session, 60L);

        assertThat(cache.get("用户", "会话").get().getMessages().get(0).getText())
                .isEqualTo("你好😀");
    }

    @Test
    public void keyPrefixIsolatesCaches() {
        RedisSessionCache other = new RedisSessionCache(template, PREFIX + "other:", true);
        cache.put(session("u1", "s1", 0L), 60L);

        assertThat(other.get("u1", "s1")).isEmpty();
    }

    @Test
    public void requireRedisTrueFailsWithoutTemplate() {
        RedisSessionCache noRedis = new RedisSessionCache(null, PREFIX, true);

        assertThatThrownBy(() -> noRedis.get("u1", "s1"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("需要 Redis");
        assertThatThrownBy(() -> noRedis.put(session("u1", "s1", 0L), 60L))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("需要 Redis");
        assertThatThrownBy(() -> noRedis.delete("u1", "s1"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("需要 Redis");
    }

    @Test
    public void requireRedisFalseDegradesWithoutTemplate() {
        RedisSessionCache noRedis = new RedisSessionCache(null, PREFIX, false);

        assertThat(noRedis.get("u1", "s1")).isEmpty();
        noRedis.put(session("u1", "s1", 0L), 60L);
        noRedis.delete("u1", "s1");
    }

    @Test
    public void cachingStoreWritesThroughAndBackfills() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60L);
        AgentSession session = session("u1", "s1", 0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));

        store.save(session);
        assertThat(cache.get("u1", "s1")).isPresent();

        cache.delete("u1", "s1");
        assertThat(store.find("u1", "s1")).isPresent();
        assertThat(cache.get("u1", "s1")).isPresent();
    }

    @Test
    public void cachingStoreMessageCrudUpdatesRedisCopy() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60L);
        store.save(session("u1", "s1", 0L));
        AgentSessionMessage added = AgentSessionMessage.user("added");

        store.appendMessage("u1", "s1", added);
        assertThat(store.findMessage("u1", "s1", added.getId())).isPresent();
        assertThat(cache.get("u1", "s1").get().getMessages()).hasSize(1);

        store.replaceMessage("u1", "s1", AgentSessionMessage.of(
                added.getId(), io.github.yinwenyuan28.ailite.llm.LlmRole.USER,
                "changed", null, null, null));
        assertThat(cache.get("u1", "s1").get().getMessages().get(0).getText())
                .isEqualTo("changed");

        store.deleteMessage("u1", "s1", added.getId());
        assertThat(cache.get("u1", "s1").get().getMessages()).isEmpty();
    }

    @Test
    public void cachingStoreFallsBackWhenCacheThrows() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        SessionCache failing = new SessionCache() {
            @Override
            public Optional<AgentSession> get(String userId, String sessionId) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public void put(AgentSession session, long ttlSeconds) {
                throw new IllegalStateException("cache down");
            }

            @Override
            public void delete(String userId, String sessionId) {
                throw new IllegalStateException("cache down");
            }
        };
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, failing, 60L);
        AgentSession session = session("u1", "s1", 0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));

        store.save(session);

        assertThat(store.find("u1", "s1")).isPresent();
        store.delete("u1", "s1");
        assertThat(store.find("u1", "s1")).isEmpty();
    }

    @Test
    public void cachingStoreKeepsVersionSemantics() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 60);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60L);
        AgentSession first = session("u1", "s1", 0L);
        first.setMessages(Collections.singletonList(AgentSessionMessage.user("a")));

        store.save(first);
        AgentSession loaded = store.find("u1", "s1").get();
        assertThat(loaded.getVersion()).isEqualTo(1L);

        loaded.setMessages(Collections.singletonList(AgentSessionMessage.user("b")));
        store.save(loaded);
        assertThat(store.find("u1", "s1").get().getVersion()).isEqualTo(2L);

        assertThatThrownBy(() -> store.save(first))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    public void cachingStoreTtlExpiresCacheButDurableKeepsData() throws Exception {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 600);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 1L);
        AgentSession session = session("u1", "s1", 0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));

        store.save(session);
        assertThat(cache.get("u1", "s1")).isPresent();

        Thread.sleep(1500L);

        assertThat(cache.get("u1", "s1")).isEmpty();
        assertThat(durable.find("u1", "s1")).isPresent();
        assertThat(store.find("u1", "s1")).isPresent();
        assertThat(cache.get("u1", "s1")).isPresent();
    }

    @Test
    public void agentSessionLoopPersistsThroughRedisCache() {
        InMemoryAgentSessionStore durable = new InMemoryAgentSessionStore(10, 600);
        CachingAgentSessionStore store = new CachingAgentSessionStore(durable, cache, 60L);
        LlmGateway gateway = gateway();
        AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway), store);

        AgentSession created = loop.ensureSession("u1", "s1", "biz");
        assertThat(created.getVersion()).isEqualTo(1L);
        assertThat(cache.get("u1", "s1")).isPresent();

        AgentSessionLoop.ResumeResult first = loop.resume("u1", "s1",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hello")));
        assertThat(first.getResult().getContent()).isEqualTo("redis-ok");
        assertThat(first.getSession().getVersion()).isEqualTo(2L);
        assertThat(first.getSession().getMessages()).hasSize(2);

        AgentSessionLoop.ResumeResult second = loop.resume("u1", "s1",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("again")));
        assertThat(second.getSession().getVersion()).isEqualTo(3L);
        assertThat(second.getSession().getMessages()).hasSize(4);
        assertThat(cache.get("u1", "s1").get().getMessages()).hasSize(4);
    }

    private static LlmGateway gateway() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("biz"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(false);
        model.setInput(Collections.singletonList("text"));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("fake");
        provider.setBaseUrl("http://localhost");
        provider.setApiKey("test");
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        profile.setThinkingLevel("off");
        llm.getProfiles().put("main", profile);

        LlmTransport transport = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                return new LlmCompletion("redis-ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
            }
        };
        return new LlmGateway(new ModelResolver(llm), Collections.singletonList(transport),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static AgentSession session(String userId, String sessionId, long version) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setVersion(version);
        return session;
    }

    private static void cleanup() {
        if (template == null) {
            return;
        }
        Set<String> keys = template.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private static String property(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.isEmpty() ? fallback : value;
    }
}

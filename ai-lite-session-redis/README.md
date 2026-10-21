# ai-lite-session-redis

`SessionCache` 的 Redis 实现。

```java
SessionCache cache = new RedisSessionCache(stringRedisTemplate, "ai-lite:session:", true);
AgentSessionStore store = new CachingAgentSessionStore(durableStore, cache, 604800L);
```

- 只存会话快照，不承担版本校验；版本由 `AgentSessionStore` 负责。
- 缓存里的损坏 JSON 按 miss 处理并删除，不影响主流程。
- `requireRedis=true` 时 Redis 不可用会抛 `LlmCallException`；`false` 时降级为 miss。

## 集成测试

默认连接 `127.0.0.1:6379` 的 DB 3：

```bash
REDIS_PASSWORD=your-password mvn -B -pl ai-lite-session-redis -am test
```

也可以用系统属性覆盖：

```bash
mvn -B -pl ai-lite-session-redis -am test \
  -Dredis.host=127.0.0.1 \
  -Dredis.port=6379 \
  -Dredis.password=your-password \
  -Dredis.database=3
```

支持的环境变量：`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`。

测试只操作 `ai-lite:test:redis:` 前缀的 key，结束后会清理，不会清库。Redis 不可用时整类跳过。

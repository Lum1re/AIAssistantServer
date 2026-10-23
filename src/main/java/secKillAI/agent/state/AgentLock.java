package secKillAI.agent.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 用户级并发锁：同一个用户，同一时间只处理一条消息。
 *
 * <p><b>为什么需要</b>：状态存在 Redis 里，而"读状态 → 改状态 → 写状态"不是一个原子操作。
 * 两个请求同时进来会出现：
 * <pre>
 *   请求 A：load → IDLE
 *   请求 B：load → IDLE          ← 同时读到旧值
 *   请求 A：save → ASKING
 *   请求 B：save → 覆盖 A 的结果
 * </pre>
 * 读操作下最坏是状态乱一下；等写操作（退款/下单）接管后，最坏是<b>重复退款</b>。
 *
 * <p><b>为什么用 Redis 而不是 synchronized</b>：synchronized 只锁得住同一个 JVM，
 * 多实例部署时失效。Redis 是共享的，两台机器看到的是同一把锁。
 *
 * <p><b>为什么还要 TTL</b>：正常路径在 {@code doFinally} 里主动释放；但如果进程崩了、
 * 线程被 kill，释放代码没跑，锁就永久卡死。TTL 是兜底，最多卡 15 秒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLock {

    private static final String KEY_PREFIX = "ai:lock:";

    /**
     * 只删"自己拿到的"那把锁。
     *
     * <p>不能直接 DEL：如果我们的锁已经过期、别人拿到了新锁，直接删会把别人的锁删掉。
     * 所以 value 存一个随机 token，删之前比对一下。用 Lua 是为了让"比对 + 删除"原子完成。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${ai.agent.lock-ttl-seconds:15}")
    private long ttlSeconds;

    /**
     * 尝试加锁。
     *
     * @return 拿到锁返回 token（释放时要带回来）；已被占用返回 {@code null}
     */
    public String tryLock(Long userId) {
        if (userId == null) {
            return UUID.randomUUID().toString();
        }
        try {
            String token = UUID.randomUUID().toString();
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(key(userId), token, Duration.ofSeconds(ttlSeconds));
            // setIfAbsent = SET NX EX：key 不存在才设置成功，第一个请求拿到，其余失败
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            // 锁本身不该让服务不可用：Redis 出问题时放行，靠 TTL 兜底
            log.warn("加锁失败，放行本次请求 userId={}：{}", userId, e.toString());
            return "no-lock";
        }
    }

    /** 释放锁。只有 token 匹配才真正删除。 */
    public void unlock(Long userId, String token) {
        if (userId == null || token == null || "no-lock".equals(token)) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key(userId)), token);
        } catch (Exception e) {
            log.warn("释放锁失败 userId={}：{}", userId, e.toString());
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

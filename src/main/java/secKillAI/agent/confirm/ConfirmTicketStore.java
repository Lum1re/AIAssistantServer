package secKillAI.agent.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import secKillAI.agent.Intent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 确认票据的存取。
 *
 * <p>存储：Redis {@code ai:confirm:{userId}}，TTL 5 分钟，一个用户同时只有一张。
 *
 * <p><b>为什么"取出即删"要用 Lua</b>：先 GET 再 DEL 是两步，中间如果进程被杀或者请求重试，
 * 同一张票据可能被消费两次 —— 那就是<b>退两次钱</b>。
 * 用 Lua 让"读 + 删"在 Redis 里一次完成，从根上避免重复消费。
 *
 * <p>这和 {@code AgentLock} 用 Lua 是同一个理由：跨进程的"检查后再操作"必须原子。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmTicketStore {

    private static final String KEY_PREFIX = "ai:confirm:";

    /** 读取并删除，一次完成。返回 nil 时 Java 侧映射成 null。 */
    private static final RedisScript<String> TAKE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.agent.confirm-ttl-minutes:5}")
    private long ttlMinutes = 5;

    /**
     * 创建票据并覆盖该用户原有的待确认操作。
     *
     * <p>失败时**抛异常**而不是静默返回 null：如果票据存不进去，我们就不能对用户说
     * "请确认" —— 否则用户确认了也没有任何凭据可执行，体验是"点了没反应"。
     */
    public ConfirmTicket create(Long userId, Intent intent, Map<String, Object> payload) {
        ConfirmTicket ticket = new ConfirmTicket(
                UUID.randomUUID().toString(),
                userId,
                intent,
                payload,
                Instant.now().plus(Duration.ofMinutes(ttlMinutes)));

        try {
            String json = objectMapper.writeValueAsString(ticket);
            persist(userId, json);
            log.debug("票据已创建 userId={} intent={}", userId, intent);
            return ticket;
        } catch (Exception e) {
            log.error("创建确认票据失败 userId={} intent={}", userId, intent, e);
            throw new IllegalStateException("创建确认票据失败", e);
        }
    }

    /**
     * 取出并删除（一次性消费）。
     *
     * @return 有效票据；没有、已过期、解析失败都返回 {@code null}
     */
    public ConfirmTicket take(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return toTicket(userId, fetchAndDelete(userId), true);
        } catch (Exception e) {
            log.error("取出确认票据失败 userId={}", userId, e);
            return null;
        }
    }

    /** 只读查看，不消费。用于日志排查或"要不要提醒用户继续"这类判断。 */
    public ConfirmTicket peek(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return toTicket(userId, fetch(userId), true);
        } catch (Exception e) {
            log.warn("查看确认票据失败 userId={}：{}", userId, e.toString());
            return null;
        }
    }

    /** 主动作废（用户说"不办了"、任务完成）。 */
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            remove(userId);
        } catch (Exception e) {
            log.warn("清除确认票据失败 userId={}：{}", userId, e.toString());
        }
    }

    // ==================== 内部：Redis 读写与解析 ====================

    /** 原子地读取并删除。单独抽出来是为了测试时能替换掉 Redis。 */
    protected String fetchAndDelete(Long userId) {
        return redisTemplate.execute(TAKE_SCRIPT, List.of(key(userId)));
    }

    /** 只读获取。 */
    protected String fetch(Long userId) {
        return redisTemplate.opsForValue().get(key(userId));
    }

    /** 写入并设置 TTL。 */
    protected void persist(Long userId, String json) {
        redisTemplate.opsForValue().set(key(userId), json, Duration.ofMinutes(ttlMinutes));
    }

    /** 删除。 */
    protected void remove(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private ConfirmTicket toTicket(Long userId, String json, boolean rejectExpired) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        ConfirmTicket ticket;
        try {
            ticket = objectMapper.readValue(json, ConfirmTicket.class);
        } catch (Exception e) {
            log.warn("确认票据解析失败，按不存在处理 userId={}：{}", userId, e.toString());
            return null;
        }
        // 防跨用户：即使 key 写错了，也不接受别人的票据
        if (ticket.userId() != null && !ticket.userId().equals(userId)) {
            log.warn("票据归属不匹配，已拒绝 userId={} ticketUser={}", userId, ticket.userId());
            return null;
        }
        if (rejectExpired && ticket.isExpired()) {
            log.info("确认票据已过期 userId={} expireAt={}", userId, ticket.expireAt());
            return null;
        }
        return ticket;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

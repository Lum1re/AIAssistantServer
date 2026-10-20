package secKillAI.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 会话状态的读写。
 *
 * <p>存 Redis，一个用户一个 key，TTL 默认 30 分钟。选 Redis 而不是 MongoDB 的理由：
 * <ul>
 *   <li>它天然带过期时间，用户半小时不说话，状态自动消失，不需要定时任务清理</li>
 *   <li>它和"聊天记录"是两套东西，混在一起会让窗口裁剪把状态一起裁掉</li>
 * </ul>
 *
 * <p><b>容错原则</b>：读写失败都不抛异常，一律当成"空闲状态"。
 * 状态丢了最坏结果是用户要重说一遍，而抛异常会让整个客服不可用——轻重很清楚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStateStore {

    private static final String KEY_PREFIX = "ai:agent:state:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.agent.state-ttl-minutes:30}")
    private long ttlMinutes;

    /** 读取状态。读不到、读坏了、Redis 挂了，都返回空闲状态。 */
    public AgentState load(Long userId) {
        if (userId == null) {
            return AgentState.idle(null);
        }
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (!StringUtils.hasText(json)) {
                return AgentState.idle(userId);
            }
            return objectMapper.readValue(json, AgentState.class);
        } catch (Exception e) {
            log.warn("读取会话状态失败，按空闲处理 userId={}：{}", userId, e.toString());
            return AgentState.idle(userId);
        }
    }

    /** 保存状态，并刷新过期时间。写失败只记日志，不影响本次回复。 */
    public void save(Long userId, AgentState state) {
        if (userId == null || state == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key(userId), json, Duration.ofMinutes(ttlMinutes));
            log.debug("会话状态已保存 userId={} stage={} missingSlot={}",
                    userId, state.stage(), state.missingSlot());
        } catch (Exception e) {
            log.error("保存会话状态失败 userId={}：{}", userId, e.toString());
        }
    }

    /** 清空状态（任务完成、用户放弃）。 */
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (Exception e) {
            log.warn("清空会话状态失败 userId={}：{}", userId, e.toString());
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

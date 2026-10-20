package secKillAI.web.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import secKillAI.web.service.ISessionService;

@Slf4j
@Service
public class SessionServiceImpl implements ISessionService {
    /** 和秒杀项目保持一致的 key 前缀 */
    private static final String USER_TOKEN_PREFIX = "userToken:";

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Override
    public Long resolvePhone(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(USER_TOKEN_PREFIX + token);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            JsonNode phone = objectMapper.readTree(json).get("phone");
            return phone == null || phone.isNull() ? null : phone.asLong();
        } catch (Exception e) {
            log.warn("解析登录态失败：{}", e.getMessage());
            return null;
        }
    }
}

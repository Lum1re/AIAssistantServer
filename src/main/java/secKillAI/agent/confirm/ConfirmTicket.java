package secKillAI.agent.confirm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import secKillAI.agent.Intent;

import java.time.Instant;
import java.util.Map;

/**
 * 一张"待用户确认的操作凭证"。
 *
 * <p>它存在的唯一原因：用户说"确认"的时候，只有两个字。**待办是什么，必须由服务端记住，
 * 不能让模型决定。**
 *
 * <p>几个关键约束（缺一个就不安全）：
 * <ul>
 *   <li>{@code userId}：票据属于谁。别人拿到也用不了。</li>
 *   <li>{@code payload}：要做的事的全部参数（订单号 / 秒杀商品 ID / 金额…）。
 *       执行时只认这里面的值，<b>不接受模型新传的参数</b>。</li>
 *   <li>{@code expireAt}：过期时间。用户在确认页放太久，数据可能已经变了。</li>
 * </ul>
 *
 * <p>模型从头到尾<b>拿不到 ticketId</b>，也不需要拿到 —— 它没有"确认"这个动作。
 */
public record ConfirmTicket(
        String ticketId,
        Long userId,
        Intent intent,
        Map<String, Object> payload,
        Instant expireAt
) {

    public ConfirmTicket {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /**
     * 是否已过期。Redis 的 TTL 是主要保障，这里再兜一次（时钟/手动写入等场景）。
     *
     * <p>必须标 {@code @JsonIgnore}：Jackson 会把 {@code isXxx()} 当成布尔属性
     * 序列化成 {@code "expired":true}，而 record 的构造器没有这个参数，
     * 反序列化就会抛 UnrecognizedPropertyException（和 AgentState 踩过同一个坑）。
     */
    @JsonIgnore
    public boolean isExpired() {
        return expireAt == null || Instant.now().isAfter(expireAt);
    }

    /** 给日志用的摘要：不打印金额明细，只说明要做什么。 */
    @JsonIgnore
    public String summary() {
        return intent + " " + payload;
    }
}

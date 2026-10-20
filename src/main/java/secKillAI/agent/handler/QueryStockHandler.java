package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.support.ProductResolver;

import java.util.List;

/**
 * 查实时库存（只读，数据来自 Redis）。
 *
 * <p>执行链比前两个长：用户通常说「那个耳机还有货吗」，不会给秒杀商品 ID，所以要先解析商品。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryStockHandler implements IntentHandler {

    static final String SLOT_KEYWORD = "productKeyword";

    private static final String STOCK_KEY_PREFIX = "seckillStockCount:";

    private final ProductResolver productResolver;
    private final StringRedisTemplate redisTemplate;

    @Override
    public Intent intent() {
        return Intent.QUERY_STOCK;
    }

    @Override
    public HandlerOutcome handle(AgentContext context) {
        Long seckillId = context.longVal("seckillId");
        Integer time = context.time();
        String keyword = context.str("productKeyword");

        if (seckillId != null && time != null) {
            return HandlerOutcome.reply(readStock(time, seckillId));
        }

        if (seckillId == null && !StringUtils.hasText(keyword)) {
            return HandlerOutcome.ask(
                    "你想查哪件商品的库存？可以说商品名，或者告诉我是哪个场次的哪一件。",
                    SLOT_KEYWORD);
        }

        List<ProductResolver.Hit> hits = keyword == null
                ? productResolver.listOf(null).stream()
                        .filter(hit -> seckillId.equals(hit.seckillId()))
                        .toList()
                : productResolver.match(time, keyword);

        if (hits.isEmpty()) {
            return HandlerOutcome.reply(notFound(context));
        }
        if (hits.size() > 1) {
            return HandlerOutcome.ask(askWhichOne(hits), SLOT_KEYWORD);
        }
        ProductResolver.Hit hit = hits.get(0);
        return HandlerOutcome.reply(readStock(hit.time(), hit.seckillId()));
    }

    /** 返回一段话术（不涉及追问，追问在 handle 里处理）。 */
    private String readStock(int time, Long seckillId) {
        String key = STOCK_KEY_PREFIX + time;
        Object value = redisTemplate.opsForHash().get(key, String.valueOf(seckillId));
        if (value == null || !StringUtils.hasText(value.toString())) {
            return "没有查到这件商品的库存，可能不属于本场次。";
        }
        int remaining;
        try {
            remaining = Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("库存字段不是数字：key={} field={} value={}", key, seckillId, value);
            return "库存数据异常，请稍后再试。";
        }
        if (remaining <= 0) {
            return "这件商品在 " + time + " 点场次已经售罄了。";
        }
        return time + " 点场次该商品实时剩余 " + remaining + " 件。";
    }

    private String notFound(AgentContext context) {
        String keyword = context.str("productKeyword");
        Integer time = context.time();
        if (StringUtils.hasText(keyword)) {
            return (time == null ? "" : time + " 点场次") + "里没找到「" + keyword + "」，可以说得再具体一点。";
        }
        return "没找到这件商品，请告诉我是哪个场次的哪一件。";
    }

    private String askWhichOne(List<ProductResolver.Hit> hits) {
        StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 件相关的商品，你要查哪一件？\n");
        for (ProductResolver.Hit hit : hits) {
            sb.append("- ").append(hit.name())
                    .append("（").append(hit.time()).append(" 点场次，秒杀商品ID ")
                    .append(hit.seckillId()).append("）\n");
        }
        return sb.toString();
    }
}

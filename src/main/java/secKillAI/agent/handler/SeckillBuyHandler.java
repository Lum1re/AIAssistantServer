package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.confirm.ConfirmTicket;
import secKillAI.agent.confirm.ConfirmTicketStore;
import secKillAI.agent.result.ErrorCode;
import secKillAI.agent.result.ReplyTemplates;
import secKillAI.agent.support.ProductResolver;
import secKillAI.web.service.IOrderOperateService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀下单（写操作，两阶段确认）。
 *
 * <p>执行链比退款长，因为要先"把用户说的商品"定位成"具体的秒杀商品"：
 * <pre>
 *   ① 定位商品（0 个 → 没找到；多个 → 让用户选；1 个 → 继续）
 *   ② 查实时库存（已售罄 → <b>直接结束，不给确认入口</b>）
 *   ③ 生成票据 → 回复确认话术
 *   ④ 用户确认 → doSeckill
 * </pre>
 *
 * <p>第 ② 步是刻意加的：如果先让用户确认、执行时才发现售罄，
 * 用户会觉得自己"确认了个寂寞"。先查库存能把这一步省掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillBuyHandler implements ConfirmableHandler {

    static final String SLOT_TIME = "time";
    static final String SLOT_KEYWORD = "productKeyword";

    static final String PAYLOAD_TIME = "time";
    static final String PAYLOAD_SECKILL_ID = "seckillId";
    static final String PAYLOAD_PRODUCT_NAME = "productName";
    static final String PAYLOAD_PRICE = "seckillPrice";

    private static final String STOCK_KEY_PREFIX = "seckillStockCount:";

    private final ProductResolver productResolver;
    private final StringRedisTemplate redisTemplate;
    private final IOrderOperateService orderOperateService;
    private final ConfirmTicketStore ticketStore;

    @Override
    public Intent intent() {
        return Intent.SECKILL_BUY;
    }

    // ==================== 第一步：定位商品 + 查库存 + 生成票据 ====================

    @Override
    public HandlerOutcome handle(AgentContext context) {
        Integer time = context.time();
        String keyword = context.str("productKeyword");
        Long seckillId = context.longVal("seckillId");

        // ① 定位商品
        ProductResolver.Hit hit;
        if (seckillId != null && time != null) {
            hit = findBySeckillId(time, seckillId);
            if (hit == null) {
                // 编号是模型猜的、或者用户记错了。别让用户去查编号 —— 直接引导说商品名
                return HandlerOutcome.reply(
                        "没找到编号为 " + seckillId + " 的商品。\n"
                                + "你可以直接告诉我商品名（比如「蓝牙耳机」），我帮你找。");
            }
        } else {
            if (time == null) {
                return HandlerOutcome.ask("请问是 10 点、12 点还是 14 点场次？", SLOT_TIME);
            }
            if (!StringUtils.hasText(keyword)) {
                return HandlerOutcome.ask(
                        "你要抢的是哪件商品？直接说商品名就行（比如「蓝牙耳机」），不用记商品编号。",
                        SLOT_KEYWORD);
            }
            List<ProductResolver.Hit> hits = productResolver.match(time, keyword);
            if (hits.isEmpty()) {
                return HandlerOutcome.reply(
                        ErrorCode.PRODUCT_NOT_FOUND.format(time + " 点场次", keyword));
            }
            if (hits.size() > 1) {
                return HandlerOutcome.ask(askWhichOne(hits), SLOT_KEYWORD);
            }
            hit = hits.get(0);
        }

        // ② 先查库存：售罄就不给确认入口了
        Integer remaining = readStock(hit.time(), hit.seckillId());
        if (remaining != null && remaining <= 0) {
            return HandlerOutcome.reply(ErrorCode.SOLD_OUT.format());
        }

        // ③ 生成票据
        Map<String, Object> payload = new HashMap<>();
        payload.put(PAYLOAD_TIME, hit.time());
        payload.put(PAYLOAD_SECKILL_ID, hit.seckillId());
        if (hit.name() != null) {
            payload.put(PAYLOAD_PRODUCT_NAME, hit.name());
        }
        if (hit.product().getSeckillPrice() != null) {
            payload.put(PAYLOAD_PRICE, hit.product().getSeckillPrice().toPlainString());
        }

        ConfirmTicket ticket = ticketStore.create(context.userId(), Intent.SECKILL_BUY, payload);
        log.info("下单票据已生成 userId={} time={} seckillId={} ticket={}",
                context.userId(), hit.time(), hit.seckillId(), ticket.ticketId());

        return HandlerOutcome.confirm(
                hit.time() + " 点场次的 " + hit.name()
                        + "，秒杀价 " + hit.product().getSeckillPrice() + " 元"
                        + (remaining == null ? "" : "（实时剩余 " + remaining + " 件）")
                        + "，确认下单吗？回复「确认」即可抢购。",
                ticket.ticketId());
    }

    // ==================== 第二步：用户确认后下单 ====================

    @Override
    public HandlerOutcome execute(AgentContext context, ConfirmTicket ticket) {
        Integer time = asInt(ticket.payload().get(PAYLOAD_TIME));
        Long seckillId = asLong(ticket.payload().get(PAYLOAD_SECKILL_ID));

        if (time == null || seckillId == null) {
            log.error("下单票据缺少 time/seckillId userId={} ticket={}",
                    context.userId(), ticket.ticketId());
            return HandlerOutcome.reply(ReplyTemplates.FALLBACK);
        }

        // doSeckill 内部已经有防重、Redis 扣减、MySQL 扣减和失败回滚，
        // 这里不再重复校验，避免"两套判断各说各话"。
        String result = orderOperateService.doSeckill(context.userId(), time, seckillId);
        log.info("下单已执行 userId={} time={} seckillId={}", context.userId(), time, seckillId);
        return HandlerOutcome.reply(result);
    }

    // ==================== 内部工具 ====================

    private ProductResolver.Hit findBySeckillId(int time, Long seckillId) {
        return productResolver.listOf(time).stream()
                .filter(hit -> seckillId.equals(hit.seckillId()))
                .findFirst()
                .orElse(null);
    }

    /** 读 Redis 实时库存。读不到或不是数字都返回 null（交给 doSeckill 去判断）。protected 便于测试替换。 */
    protected Integer readStock(int time, Long seckillId) {
        try {
            Object value = redisTemplate.opsForHash()
                    .get(STOCK_KEY_PREFIX + time, String.valueOf(seckillId));
            if (value == null || !StringUtils.hasText(value.toString())) {
                return null;
            }
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            log.warn("读取库存失败 time={} seckillId={}：{}", time, seckillId, e.toString());
            return null;
        }
    }

    private String askWhichOne(List<ProductResolver.Hit> hits) {
        StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 件相关的商品，你要抢哪一件？\n");
        for (ProductResolver.Hit hit : hits) {
            sb.append("- ").append(hit.name())
                    .append("，秒杀价 ").append(hit.product().getSeckillPrice()).append(" 元\n");
        }
        return sb.toString();
    }

    private Integer asInt(Object value) {
        Long l = asLong(value);
        return l == null ? null : l.intValue();
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        try {
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

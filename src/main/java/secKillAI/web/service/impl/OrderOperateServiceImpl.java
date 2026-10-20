package secKillAI.web.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import secKillAI.DTO.OrderInfo;
import secKillAI.DTO.OrderInfoVo;
import secKillAI.DTO.SeckillProductVo;
import secKillAI.web.mapper.OrderInfoMapper;
import secKillAI.web.mapper.SeckillProductMapper;
import secKillAI.web.service.IOrderOperateService;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderOperateServiceImpl implements IOrderOperateService {
    private static final String STOCK_KEY_PREFIX = "seckillStockCount:";
    private static final String UNIQUE_ORDER_KEY_PREFIX = "seckillUniqueOrderset:";
    private static final String REFUND_PENDING_PREFIX = "ai:refund:pending:";

    private final OrderInfoMapper orderInfoMapper;
    private final SeckillProductMapper seckillProductMapper;
    private final StringRedisTemplate redisTemplate;
    @Override
    @Transactional
    public String doSeckill(long userId, int time, Long seckillId) {
        // 1. 找出这件秒杀商品
        List<SeckillProductVo> products = seckillProductMapper.queryByTime(time);
        SeckillProductVo product = products.stream()
                .filter(p -> p.getId().equals(seckillId))
                .findFirst().orElse(null);
        if (product == null) {
            return "没有找到这个秒杀商品，请确认场次和商品ID是否正确。";
        }

        // 2. 防重复抢购（和秒杀项目用同一套 key）
        String uniqueKey = UNIQUE_ORDER_KEY_PREFIX + time;
        String member = userId + ":" + seckillId;
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(uniqueKey, member))) {
            return "您已经抢购过这件商品了，不能重复抢购。";
        }

        // 3. 扣 Redis 库存（先扣缓存，快速失败）
        String stockKey = STOCK_KEY_PREFIX + time;
        String stockField = String.valueOf(seckillId);

        // Redis 里如果没有这件商品的库存字段，先从数据库灌一次
        // （正常情况下由定时任务预热，这里兜底，避免 HINCRBY 直接把不存在的字段算成 -1）
        if (!Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(stockKey, stockField))) {
            redisTemplate.opsForHash().put(stockKey, stockField, String.valueOf(product.getStockCount()));
        }

        Long remain = redisTemplate.opsForHash().increment(stockKey, stockField, -1);
        if (remain != null && remain < 0) {
            redisTemplate.opsForHash().increment(stockKey, stockField, 1);
            return "这件商品已经被抢完了。";
        }

        try {
            // 4. 扣数据库库存
            int decr = seckillProductMapper.decrStock(seckillId);
            if (decr == 0) {
                redisTemplate.opsForHash().increment(stockKey, stockField, 1);
                return "这件商品已经被抢完了。";
            }

            // 5. 创建订单（状态 0 = 待支付）
            OrderInfo order = new OrderInfo();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setProductId(product.getProductId());
            order.setProductImg(product.getProductImg());
            order.setProductName(product.getProductName());
            order.setProductCount(1);
            order.setProductPrice(product.getProductPrice());
            order.setSeckillPrice(product.getSeckillPrice());
            order.setIntergral(product.getSeckillPrice().longValue() * 10);
            order.setStatus(0);
            order.setSeckillTime(time);
            order.setSeckillId(seckillId);
            orderInfoMapper.insertOrder(order);

            // 6. 标记防重复
            redisTemplate.opsForSet().add(uniqueKey, member);

            return "抢购成功！\n"
                    + "- 订单号：" + order.getOrderNo() + "\n"
                    + "- 商品：" + product.getProductName() + "\n"
                    + "- 秒杀价：" + product.getSeckillPrice() + " 元\n"
                    + "- 状态：待支付（超时未支付订单会自动取消并释放库存）\n"
                    + "请提醒用户尽快完成支付。";
        } catch (RuntimeException e) {
            // ① Redis 不在事务里，必须手动回补
            redisTemplate.opsForHash().increment(stockKey, stockField, 1);
            // ② MySQL 库存不用在这里补：decrStock 和 insertOrder 在同一个事务，
            //    异常继续抛出后 Spring 会把那 -1 一起撤销。
            //    在这里再调一次 incrStock 只会被一起回滚，白写而且误导后来的人。
            log.error("下单失败，已回补 Redis 库存 userId={} time={} seckillId={}",
                    userId, time, seckillId, e);
            // ③ 必须继续抛出：catch 里 return 会让 @Transactional 正常提交，
            //    结果就是"订单没建成、库存却少了一件"。
            throw e;
        }
    }

    @Override
    public String prepareRefund(long userId, String orderNo) {
        OrderInfoVo order = orderInfoMapper.queryOrder(orderNo, userId);
        if (order == null) {
            return "没有查到订单 " + orderNo + "，请确认订单号是否正确，或者该订单不属于当前账号。";
        }
        if (order.getStatus() == null || order.getStatus() != 1) {
            return "订单 " + orderNo + " 当前是「" + statusText(order.getStatus())
                    + "」，只有已支付的订单可以退款。";
        }

        // 服务端暂存「待用户确认的退款」，5 分钟有效
        redisTemplate.opsForValue().set(REFUND_PENDING_PREFIX + userId, orderNo, Duration.ofMinutes(5));

        return "请跟用户确认以下信息：\n"
                + "- 订单号：" + order.getOrderNo() + "\n"
                + "- 商品：" + order.getProductName() + "\n"
                + "- 退款金额：" + order.getSeckillPrice() + " 元\n"
                + "确认后才执行退款。";
    }

    @Override
    @Transactional
    public String confirmRefund(long userId) {
        String key = REFUND_PENDING_PREFIX + userId;
        String orderNo = redisTemplate.opsForValue().get(key);
        if (orderNo != null) {
            redisTemplate.delete(key);
        }
        if (orderNo == null) {
            return "没有待确认的退款，可能已经超时了，请重新发起。";
        }

        OrderInfoVo order = orderInfoMapper.queryOrder(orderNo, userId);
        if (order == null) {
            return "订单不存在或不属于当前账号。";
        }
        if (order.getStatus() == null || order.getStatus() != 1) {
            return "订单当前状态已变化，无法退款。";
        }

        // 改状态：SQL 里带了 status = 1 的条件，天然防重复
        int updated = orderInfoMapper.markRefunded(orderNo, userId);
        if (updated == 0) {
            return "退款失败，订单状态可能已经变更。";
        }

        orderInfoMapper.insertRefundLog(orderNo, order.getSeckillPrice().toPlainString(), "用户申请退款");

        // 回补库存：数据库和 Redis 都要还回去
        if (order.getSeckillId() == null || order.getSeckillTime() == null) {
            log.error("订单 {} 缺少 seckillId 或 seckillTime，库存未回补！", orderNo);
        } else {
            int restored = seckillProductMapper.incrStock(order.getSeckillId());
            if (restored == 0) {
                log.error("回补数据库库存失败，seckillId={}", order.getSeckillId());
            }
            redisTemplate.opsForHash().increment(
                    STOCK_KEY_PREFIX + order.getSeckillTime(),
                    String.valueOf(order.getSeckillId()), 1);
        }

        return "退款申请已提交，订单 " + orderNo + " 的 " + order.getSeckillPrice()
                + " 元将在 1-3 个工作日内退回。";
    }

    private String generateOrderNo() {
        return String.valueOf(System.currentTimeMillis() * 1000
                + ThreadLocalRandom.current().nextInt(1000));
    }

    private String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时取消";
            case 4 -> "已退款";
            default -> "未知";
        };
    }
}

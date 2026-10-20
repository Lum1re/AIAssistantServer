package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import secKillAI.DTO.OrderInfoVo;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.support.Dict;
import secKillAI.web.mapper.OrderInfoMapper;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 查订单（只读）。
 *
 * <p>这是 P1 用来打通整条管线的最小意图：一个必填槽位（订单号）、一次数据库查询、
 * 一段模板话术。话术不交给模型生成——订单信息是结构化的，模板 0 延迟、0 成本、0 编造风险。
 *
 * <p>安全：{@code userId} 由 {@link AgentContext} 从服务端注入，SQL 里带
 * {@code AND user_id = ?}，模型既看不到也改不了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryOrderHandler implements IntentHandler {

    private static final String ORDER_NO_HINT = "请提供订单号，在「我的订单」页面可以看到（15~25 位数字）。";

    private final OrderInfoMapper orderInfoMapper;

    @Override
    public Intent intent() {
        return Intent.QUERY_ORDER;
    }

    @Override
    public Flux<String> handle(AgentContext context) {
        String orderNo = context.orderNo();
        if (orderNo == null) {
            // 槽位缺失或格式不对，追问。注意：绝不替用户猜一个订单号
            return Flux.just(ORDER_NO_HINT);
        }

        OrderInfoVo order = orderInfoMapper.queryOrder(orderNo, context.userId());
        if (order == null) {
            return Flux.just("没查到订单 " + orderNo + "，请确认订单号是否正确，或者该订单不属于当前账号。");
        }

        log.info("用户 {} 查询订单 {} 成功", context.userId(), orderNo);
        return Flux.just(format(order));
    }

    private String format(OrderInfoVo order) {
        return "订单 " + order.getOrderNo() + "：\n"
                + "- 商品：" + order.getProductName() + "\n"
                + "- 秒杀价：" + order.getSeckillPrice() + " 元\n"
                + "- 状态：" + Dict.orderStatus(order.getStatus()) + "\n"
                + "- 支付方式：" + Dict.payType(order.getPayType()) + "\n"
                + "- 下单时间：" + formatDate(order.getCreateDate());
    }

    private String formatDate(Date date) {
        return date == null ? "未知" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}

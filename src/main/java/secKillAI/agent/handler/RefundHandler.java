package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import secKillAI.DTO.OrderInfoVo;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.confirm.ConfirmTicket;
import secKillAI.agent.confirm.ConfirmTicketStore;
import secKillAI.agent.result.ErrorCode;
import secKillAI.agent.result.ReplyTemplates;
import secKillAI.agent.support.Dict;
import secKillAI.web.mapper.OrderInfoMapper;
import secKillAI.web.service.IOrderOperateService;

import java.util.HashMap;
import java.util.Map;

/**
 * 退款（写操作，两阶段确认）。
 *
 * <p>这是整个改造的落点：<b>以前"用户是否确认"由大模型判断，现在由代码判断。</b>
 *
 * <p>两阶段流程：
 * <pre>
 *   ① handle()  校验订单 → 生成服务端票据 → 回复确认话术（<b>不执行</b>）
 *   ② execute() 用户回"确认"后，由编排器调用 → 二次校验 → 真正退款
 * </pre>
 *
 * <p>关键的三个"不"：
 * <ul>
 *   <li><b>不</b>相信模型给的身份：{@code userId} 由服务端注入</li>
 *   <li><b>不</b>相信模型给的参数：执行时只用票据里的 {@code orderNo}</li>
 *   <li><b>不</b>跳过二次校验：票据可能是几分钟前生成的，订单状态可能已变</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundHandler implements ConfirmableHandler {

    static final String SLOT_ORDER_NO = "orderNo";

    static final String PAYLOAD_ORDER_NO = "orderNo";
    static final String PAYLOAD_PRODUCT_NAME = "productName";
    static final String PAYLOAD_AMOUNT = "amount";

    private static final String ORDER_NO_HINT = "请提供订单号，在「我的订单」页面可以看到（15~25 位数字）。";

    /** 订单状态：1 = 已支付。只有已支付的订单能退 */
    private static final int STATUS_PAID = 1;

    private final OrderInfoMapper orderInfoMapper;
    private final IOrderOperateService orderOperateService;
    private final ConfirmTicketStore ticketStore;

    @Override
    public Intent intent() {
        return Intent.REFUND;
    }

    // ==================== 第一步：校验 + 生成票据 ====================

    @Override
    public HandlerOutcome handle(AgentContext context) {
        String orderNo = context.orderNo();
        if (orderNo == null) {
            return HandlerOutcome.ask(ORDER_NO_HINT, SLOT_ORDER_NO);
        }

        OrderInfoVo order = orderInfoMapper.queryOrder(orderNo, context.userId());
        if (order == null) {
            // 不存在和"不是本人的"用同一句话：不能透露别人的订单是否存在
            return HandlerOutcome.reply(ErrorCode.ORDER_NOT_FOUND.format(orderNo));
        }
        if (order.getStatus() == null || order.getStatus() != STATUS_PAID) {
            return HandlerOutcome.reply(ErrorCode.ORDER_STATUS_INVALID
                    .format(orderNo, Dict.orderStatus(order.getStatus())));
        }

        // 把执行所需的全部参数固定进票据：后续只用这里的值，不再信任模型
        Map<String, Object> payload = new HashMap<>();
        payload.put(PAYLOAD_ORDER_NO, orderNo);
        if (order.getProductName() != null) {
            payload.put(PAYLOAD_PRODUCT_NAME, order.getProductName());
        }
        if (order.getSeckillPrice() != null) {
            payload.put(PAYLOAD_AMOUNT, order.getSeckillPrice().toPlainString());
        }

        ConfirmTicket ticket = ticketStore.create(context.userId(), Intent.REFUND, payload);
        log.info("退款票据已生成 userId={} orderNo={} ticket={}",
                context.userId(), orderNo, ticket.ticketId());

        return HandlerOutcome.confirm(
                "订单 " + orderNo + "：\n"
                        + "- 商品：" + order.getProductName() + "\n"
                        + "- 退款金额：" + order.getSeckillPrice() + " 元\n"
                        + "确认退款吗？回复「确认」即可办理。",
                ticket.ticketId());
    }

    // ==================== 第二步：用户确认后执行 ====================

    @Override
    public HandlerOutcome execute(AgentContext context, ConfirmTicket ticket) {
        String orderNo = asText(ticket.payload().get(PAYLOAD_ORDER_NO));
        if (orderNo == null) {
            // 票据是自己生成的，缺参数说明代码有问题，不是用户的问题
            log.error("退款票据缺少 orderNo userId={} ticket={}",
                    context.userId(), ticket.ticketId());
            return HandlerOutcome.reply(ReplyTemplates.FALLBACK);
        }

        // 二次校验：票据可能是 5 分钟前生成的，用户可能已经付过款 / 已被别人退过
        OrderInfoVo order = orderInfoMapper.queryOrder(orderNo, context.userId());
        if (order == null) {
            return HandlerOutcome.reply(ErrorCode.ORDER_NOT_FOUND.format(orderNo));
        }
        if (order.getStatus() == null || order.getStatus() != STATUS_PAID) {
            return HandlerOutcome.reply(ErrorCode.ORDER_STATUS_CHANGED.format());
        }

        // 真正的退款：改状态、写流水、回补库存；SQL 带 status = 1 保证幂等
        String result = orderOperateService.executeRefund(context.userId(), orderNo);
        log.info("退款已执行 userId={} orderNo={} result={}", context.userId(), orderNo, result);
        return HandlerOutcome.reply(result);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}

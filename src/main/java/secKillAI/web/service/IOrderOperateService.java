package secKillAI.web.service;

public interface IOrderOperateService {
    /** 秒杀下单，返回给用户看的结果文案 */
    String doSeckill(long userId, int time, Long seckillId);

    /** 第一步：准备退款，校验后返回确认文案 */
    String prepareRefund(long userId, String orderNo);

    /** 第二步：执行退款（用服务端暂存的待退订单，不接收参数） */
    String confirmRefund(long userId);
}
package secKillAI.web.service;

public interface IOrderOperateService {

    /** 秒杀下单，返回给用户看的结果文案 */
    String doSeckill(long userId, int time, Long seckillId);

    /** 第一步：准备退款，校验后返回确认文案（旧链路用，单号存在 Redis 里） */
    String prepareRefund(long userId, String orderNo);

    /** 第二步：执行退款（用服务端暂存的待退订单，不接收参数；旧链路用） */
    String confirmRefund(long userId);

    /**
     * 第二步：按订单号执行退款（新编排链路用）。
     *
     * <p>和 {@link #confirmRefund(long)} 的区别：这里的订单号由调用方传入，
     * 而调用方（RefundHandler）的订单号来自<b>服务端确认票据</b>，不是模型给的。
     *
     * <p>为什么新链路不复用 {@code confirmRefund()}：那个方法读的是
     * {@code ai:refund:pending:{userId}} 这个旧 key。新链路的待确认信息存在票据里，
     * 让新链路去写旧 key 会产生两份状态，早晚不一致。
     *
     * <p>内部的二次校验（订单存在、状态必须为已支付）和幂等（SQL 带 status = 1）完全一致。
     */
    String executeRefund(long userId, String orderNo);
}

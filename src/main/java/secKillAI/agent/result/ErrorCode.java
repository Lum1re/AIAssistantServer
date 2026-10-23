package secKillAI.agent.result;

/**
 * 所有业务错误码 + 对应的用户话术。
 *
 * <p>为什么要把话术写在枚举里：同一个错误可能在多个地方触发（比如"订单不存在"
 * 在查订单、退款、取消订单里都会出现）。如果每个地方各写一句话术，早晚会出现
 * "查订单说没查到，退款说找不到"这种不一致。
 *
 * <p>模板里的 {@code {0}}、{@code {1}} 由 {@link #format(Object...)} 填充。
 * 刻意不用 {@code MessageFormat}：它对单引号有特殊处理，中文文案里出现引号时容易踩坑。
 */
public enum ErrorCode {

    /** 订单不存在，或者不属于当前用户（SQL 带 user_id，两种情况无法区分，也不该区分） */
    ORDER_NOT_FOUND("没查到订单 {0}，请确认订单号是否正确，或者该订单不属于当前账号。"),

    /** 订单状态不允许该操作（比如只有"已支付"才能退款） */
    ORDER_STATUS_INVALID("订单 {0} 当前是「{1}」，只有已支付的订单可以退款。"),

    /** 执行前二次校验发现状态已变 */
    ORDER_STATUS_CHANGED("订单状态已变化，本次操作未执行。"),

    /** 场次里没匹配到商品 */
    PRODUCT_NOT_FOUND("{0}里没找到「{1}」，可以说得再具体一点。"),

    /** 关键词命中多个商品，需要用户选 */
    PRODUCT_AMBIGUOUS("找到 {0} 件相关的商品，你要哪一件？"),

    /** 库存不足 */
    SOLD_OUT("这件商品已经抢完了。"),

    /** 防重集合命中 */
    ALREADY_BOUGHT("你已经抢购过这件商品了，不能重复抢购。"),

    /** 确认票据不存在或已过期 */
    CONFIRM_EXPIRED("确认已超时，请重新说一次。"),

    /** 票据有效但数据已变（金额、状态对不上） */
    CONFIRM_MISMATCH("订单信息有变化，请重新发起。"),

    /** 拿不到用户锁 */
    BUSY("上一条还在处理中，请稍等一下再发。"),

    /** 系统故障。永远不要往这句话里拼接异常信息 */
    SYS_ERROR("系统有点忙，请稍后再试。");

    private final String template;

    ErrorCode(String template) {
        this.template = template;
    }

    public String template() {
        return template;
    }

    /**
     * 把参数填进模板。
     *
     * <p>参数个数不足时，没被替换的 {@code {n}} 会原样保留 —— 这比抛异常好：
     * 宁可让用户看到一句带占位符的话，也不要因为话术渲染失败而整个请求崩掉。
     */
    public String format(Object... args) {
        String text = template;
        if (args == null) {
            return text;
        }
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return text;
    }
}

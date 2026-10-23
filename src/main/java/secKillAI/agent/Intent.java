package secKillAI.agent;

import java.util.Locale;

/**
 * 用户意图。
 *
 * <p>这是「代码认识的事情」——一共 8 类，写死在枚举里，不需要建任何表。
 * 大模型只负责把用户的一句话分类到其中之一（见 {@link IntentRecognizer}），
 * 具体调哪个方法、按什么顺序调，由 {@link AgentOrchestrator} 用 switch 决定。
 *
 * <p>为什么不用数据库配置：意图是代码行为的一部分，每个意图都对应一段 Java 逻辑。
 * 放进数据库只会让「数据库里有一条意图，代码里没有对应处理器」这种错误变得可能。
 */
public enum Intent {

    /** 按订单号查订单详情。只读。 */
    QUERY_ORDER("查订单", false, "按订单号查询订单状态、商品、金额、支付方式"),

    /** 查某个场次的秒杀商品列表。只读。 */
    QUERY_PRODUCTS("查场次商品", false, "查询 10 / 12 / 14 点场次的秒杀商品列表"),

    /** 查某件商品的实时库存。只读。 */
    QUERY_STOCK("查库存", false, "查询某件秒杀商品的实时剩余库存"),

    /** 查商品参数详情。只读。 */
    QUERY_PRODUCT_DETAIL("查商品详情", false, "查询商品的参数、规格、描述等详细信息"),

    /** 秒杀下单。写操作，必须二次确认。 */
    SECKILL_BUY("秒杀下单", true,
            "帮用户抢购某件秒杀商品，属于写操作，执行前必须让用户确认。"
                    + "用户只要说商品名或场次就够了，不需要提供商品编号"),

    /** 申请退款。写操作，必须二次确认。 */
    REFUND("申请退款", true, "帮用户退掉已支付的订单，属于写操作，执行前必须让用户确认"),

    /** 活动规则、支付方式、退款政策这类可以直接回答的问题。 */
    FAQ("常见问题", false, "咨询秒杀活动规则、支付方式、退款政策等常识性问题"),

    /** 识别不出来，降级到原来的全工具对话链路。 */
    UNKNOWN("无法识别", false, "不属于以上任何一类，或者识别结果不可信");

    private final String label;
    private final boolean writeOperation;
    private final String description;

    Intent(String label, boolean writeOperation, String description) {
        this.label = label;
        this.writeOperation = writeOperation;
        this.description = description;
    }

    public String label() {
        return label;
    }

    /** 是否是对数据有副作用的写操作。写操作不允许在降级链路里执行。 */
    public boolean writeOperation() {
        return writeOperation;
    }

    public String description() {
        return description;
    }

    /**
     * 把模型输出的字符串容错地转成枚举。
     *
     * <p>模型偶尔会输出小写、带空格、或者干脆编一个不存在的名字。
     * 这些情况一律返回 {@link #UNKNOWN}，绝不抛异常——意图识别失败不应该让接口挂掉。
     */
    public static Intent parse(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (Intent intent : values()) {
            if (intent.name().equals(normalized)) {
                return intent;
            }
        }
        return UNKNOWN;
    }
}

package secKillAI.agent.support;

/**
 * 枚举值 → 中文文案的字典。
 *
 * <p>这些文案原来散落在 {@code OrderTool} 的私有方法里。抽出来是为了让编排链路的
 * 各个处理器共用同一份定义，避免「查订单说已支付、退款说已付款」这种不一致。
 */
public final class Dict {

    private Dict() {
    }

    /** 订单状态：0 待支付 / 1 已支付 / 2 已取消 / 3 已超时取消 / 4 已退款 */
    public static String orderStatus(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待支付（超时未支付会自动取消）";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时取消";
            case 4 -> "已退款";
            default -> "未知状态(" + status + ")";
        };
    }

    /** 支付方式：0 在线支付 / 1 积分支付 */
    public static String payType(Integer payType) {
        if (payType == null) {
            return "未支付";
        }
        return switch (payType) {
            case 0 -> "在线支付（支付宝）";
            case 1 -> "积分支付";
            default -> "未知(" + payType + ")";
        };
    }

    /** 场次的展示文案 */
    public static String timeLabel(Integer time) {
        return time == null ? "全部场次" : time + " 点场次";
    }
}

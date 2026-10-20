package secKillAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import secKillAI.DTO.OrderInfoVo;
import secKillAI.web.mapper.OrderInfoMapper;

/**
 * 订单查询工具。
 * 不加 @Component：每个请求手动 new 一个，把当前用户的手机号绑进去，保证用户之间不串。
 */
public class OrderTool {

    private final OrderInfoMapper orderInfoMapper;
    private final Long userId;

    public OrderTool(OrderInfoMapper orderInfoMapper, Long userId) {
        this.orderInfoMapper = orderInfoMapper;
        this.userId = userId;
    }

    @Tool(name = "查询订单状态",value = "根据订单号查询订单状态、商品和金额，只能查当前登录用户自己的订单")
    public String queryOrder(@P("订单号") String orderNo) {
        try{
            OrderInfoVo orderInfoVo = orderInfoMapper.queryOrder(orderNo, userId);
            if(orderInfoVo==null){
                return "没有查到订单 " + orderNo + "，请确认订单号是否正确，或者该订单不属于当前账" +
                        "号。";
            }
            return "订单 " + orderInfoVo.getOrderNo() + "：\n"
                    + "- 商品：" + orderInfoVo.getProductName() + "\n"
                    + "- 秒杀价：" + orderInfoVo.getSeckillPrice() + " 元\n"
                    + "- 状态：" + orderStatus(orderInfoVo.getStatus()) + "\n"
                    + "- 支付方式：" + payType(orderInfoVo.getPayType()) + "\n"
                    + "- 下单时间：" + orderInfoVo.getCreateDate();
        } catch (Exception e) {
            e.printStackTrace();
            return "查询订单失败，请稍后再试。";
        }
    }
    private String orderStatus(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待支付（超时未支付会自动取消）";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时取消";
            case 4 -> "已退款";
            default -> "未知状态(" + status + ")";
        };
    }

    private String payType(Integer payType) {
        if (payType == null) return "未支付";
        return switch (payType) {
            case 0 -> "在线支付（支付宝）";
            case 1 -> "积分支付";
            default -> "未知(" + payType + ")";
        };
    }
}

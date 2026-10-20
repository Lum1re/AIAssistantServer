package secKillAI.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import secKillAI.DTO.OrderInfo;
import secKillAI.DTO.OrderInfoVo;

@Mapper
public interface OrderInfoMapper {
    // 查询订单
    OrderInfoVo queryOrder(@Param("orderNo") String orderNo, @Param("userId") long userId);

    // 下单
    int insertOrder(OrderInfo order);

    /** 只有状态为 1（已支付）且属于本人的订单才能退款，返回影响行数 */
    int markRefunded(@Param("orderNo") String orderNo, @Param("userId") long userId);

    int insertRefundLog(@Param("outTradeNo") String outTradeNo,
                        @Param("refundAmount") String refundAmount,
                        @Param("refundReason") String refundReason);
}
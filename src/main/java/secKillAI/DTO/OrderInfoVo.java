package secKillAI.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单查询结果。
 *
 * 注意：这里的字段要和 OrderInfoMapper.xml 中 queryOrder 的 SELECT 列对应上。
 * 少查一个字段（尤其是 seckillId / seckillTime），退款时回补库存就会静默失败。
 */
@Data
public class OrderInfoVo {
    private String orderNo;
    private Long userId;
    private Long productId;
    private String productImg;
    private String productName;
    private Integer productCount;
    private BigDecimal productPrice;
    private BigDecimal seckillPrice;
    private Long intergral;
    private Integer status;
    private Integer payType;
    private Date createDate;
    private Date payDate;
    private Integer seckillTime;
    private Long seckillId;
}

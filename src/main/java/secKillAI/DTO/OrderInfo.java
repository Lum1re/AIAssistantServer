package secKillAI.DTO;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class OrderInfo {
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
    private Date createDate;
    private Date seckillDate;
    private Integer seckillTime;
    private Long seckillId;
}
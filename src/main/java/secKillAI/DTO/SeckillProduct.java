package secKillAI.DTO;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("t_seckill_product")
public class SeckillProduct {
    @TableId
    private Long id;
    private Long productId;
    private BigDecimal seckillPrice;
    private Integer stockCount;
    private Integer time;
}
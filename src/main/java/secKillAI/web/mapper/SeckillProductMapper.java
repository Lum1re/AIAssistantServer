package secKillAI.web.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import secKillAI.DTO.SeckillProduct;
import secKillAI.DTO.SeckillProductVo;

import java.util.List;
@Mapper
public interface SeckillProductMapper extends BaseMapper<SeckillProduct> {
    List<SeckillProductVo> queryByTime(@Param("time") int time);

    List<SeckillProductVo> queryProductsByIds(@Param("ids") List<Long> ids);

    int decrStock(@Param("seckillId") Long seckillId);

    int incrStock(@Param("seckillId") Long seckillId);
}

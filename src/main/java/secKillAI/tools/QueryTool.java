package secKillAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import secKillAI.DTO.SeckillProductVo;
import secKillAI.web.mapper.SeckillProductMapper;

import java.util.List;

@Component
public class QueryTool {
    @Autowired
    private SeckillProductMapper seckillProductMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Tool(name = "查询场次商品", value = "根据用户提出的时间，调用秒杀服务的queryByTime查询该时间段的商品")
    public String queryProductByTime(@P("秒杀场次") int time) {
        if (time != 10 && time != 12 && time != 14) {
            return "秒杀场次只能是 10、12 或 14。";
        }
        try {
            List<SeckillProductVo> seckillProductVos = seckillProductMapper.queryByTime(time);
            if (seckillProductVos.size() == 0) {
                return time + " 点场次当前没有秒杀商品。";
            }
            StringBuilder sb = new StringBuilder(time + " 点场次的秒杀商品共 ")
                    .append(seckillProductVos.size()).append(" 件：\n");
            for (SeckillProductVo p : seckillProductVos) {
                sb.append("- ").append(p.getProductName())
                        .append("，秒杀价 ").append(p.getSeckillPrice()).append(" 元")
                        .append("，原价 ").append(p.getProductPrice()).append(" 元")
                        .append("（秒杀商品ID ").append(p.getId()).append("）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "查询秒杀商品失败，请稍后再试。";
        }
    }

    @Tool(name = "查询商品库存", value = "根据查询的场次和查询的商品，去redis中查询对应的预库存")
    public String queryProductStock(@P("秒杀场次") int time,
                                    @P("秒杀商品ID") Long secKillId) {
        if (secKillId == null) {
            return "缺少秒杀商品ID，无法查询库存。";
        }
        try {
            Object stock = redisTemplate.opsForHash()
                    .get("seckillStockCount:" + time, String.valueOf(secKillId));
            if (stock == null || !StringUtils.hasText(stock.toString())) {
                return "没有查到该商品的库存，可能不属于本场次。";
            }
            int remaining = Integer.parseInt(stock.toString());
            return remaining <= 0
                    ? "该商品在 " + time + " 点场次已经售罄了。"
                    : time + " 点场次该商品实时剩余 " + remaining + " 件。";
        } catch (Exception e) {
            e.printStackTrace();
            return "查询库存失败，请稍后再试。";
        }
    }

    @Tool(name = "查询商品详情", value = "根据要查的商品编号，调用商品服务中的queryByIds方法查询对应的商品信息集合")
    public String queryProductByIds(@P("商品集合") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "请提供要查询的商品ID。";
        }
        try {
            List<SeckillProductVo> products = seckillProductMapper.queryProductsByIds(ids);
            if (products.isEmpty()) {
                return "没有查到这些商品的信息。";
            }
            StringBuilder sb = new StringBuilder();
            for (SeckillProductVo p : products) {
                String detail = p.getProductDetail();
                if (detail != null && detail.length() > 150) {
                    detail = detail.substring(0, 150) + "...";
                }
                sb.append("【").append(p.getProductName()).append("】")
                        .append("价格 ").append(p.getProductPrice()).append(" 元\n")
                        .append("标题：").append(p.getProductTitle()).append("\n")
                        .append("详情：").append(detail).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "查询商品详情失败，请稍后再试。";
        }
    }
}


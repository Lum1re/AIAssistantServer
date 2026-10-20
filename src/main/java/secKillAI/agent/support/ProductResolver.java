package secKillAI.agent.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import secKillAI.DTO.SeckillProductVo;
import secKillAI.web.mapper.SeckillProductMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品解析器：把「用户说的关键词」变成「具体的秒杀商品」。
 *
 * <p>四个只读意图里有三个都要先定位商品（查库存、查详情、以后的下单），
 * 所以把这层逻辑收在一处，避免每个处理器各写一遍模糊匹配。
 *
 * <p>⚠️ 两个 ID 必须在类型上分开，不能混用：
 * <ul>
 *   <li>{@code seckillId} = {@code t_seckill_product.id}，用于查库存、下单、防重</li>
 *   <li>{@code productId} = {@code s-product.t_product.id}，用于查商品详情</li>
 * </ul>
 * 现有代码把这两个混着用（{@code QueryTool} 把 seckillId 当商品 ID 传给查详情的工具），
 * 是模型最容易踩的坑，这里从 API 上就区分开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductResolver {

    /** 系统只开这三个场次 */
    private static final int[] TIMES = {10, 12, 14};

    private final SeckillProductMapper seckillProductMapper;

    /**
     * 一条匹配结果。带上场次是因为 {@code queryByTime} 的 SQL 没有 select time 字段，
     * 跨场次搜索时得由调用方记住这条是从哪个场次查出来的。
     */
    public record Hit(int time, SeckillProductVo product) {

        public Long seckillId() {
            return product.getId();
        }

        public Long productId() {
            return product.getProductId();
        }

        public String name() {
            return product.getProductName();
        }
    }

    /**
     * 列出商品。
     *
     * @param time 场次，null 表示三个场次全搜
     */
    public List<Hit> listOf(Integer time) {
        List<Hit> hits = new ArrayList<>();
        for (int t : time == null ? TIMES : new int[]{time}) {
            List<SeckillProductVo> products = seckillProductMapper.queryByTime(t);
            if (products == null) {
                continue;
            }
            for (SeckillProductVo product : products) {
                hits.add(new Hit(t, product));
            }
        }
        return hits;
    }

    /**
     * 按关键词模糊匹配商品。
     *
     * <p>匹配商品名和商品标题，忽略大小写。关键词为空等同于「列出全部」，
     * 因为用户可能直接说「10 点有什么」。
     */
    public List<Hit> match(Integer time, String keyword) {
        List<Hit> all = listOf(time);
        if (!StringUtils.hasText(keyword)) {
            return all;
        }
        String kw = keyword.trim().toLowerCase();
        List<Hit> matched = new ArrayList<>();
        for (Hit hit : all) {
            if (contains(hit.product().getProductName(), kw)
                    || contains(hit.product().getProductTitle(), kw)) {
                matched.add(hit);
            }
        }
        return matched;
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword);
    }
}

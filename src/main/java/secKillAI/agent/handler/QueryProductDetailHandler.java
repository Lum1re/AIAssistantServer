package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import secKillAI.DTO.SeckillProductVo;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.support.ProductResolver;
import secKillAI.web.mapper.SeckillProductMapper;

import java.util.List;

/**
 * 查商品详情（只读）。
 *
 * <p>关键点：这里要的是 **productId**（{@code s-product.t_product.id}），
 * 不是 seckillId。用户说商品名时，先经过 {@link ProductResolver} 找到秒杀商品，
 * 再从里面把 productId 取出来——不让模型去猜是哪套 ID。
 */
@Component
@RequiredArgsConstructor
public class QueryProductDetailHandler implements IntentHandler {

    private static final int MAX_DETAIL_LENGTH = 150;

    private final ProductResolver productResolver;
    private final SeckillProductMapper seckillProductMapper;

    @Override
    public Intent intent() {
        return Intent.QUERY_PRODUCT_DETAIL;
    }

    @Override
    public Flux<String> handle(AgentContext context) {
        Long productId = context.longVal("productId");

        if (productId == null) {
            String keyword = context.str("productKeyword");
            if (keyword == null) {
                return Flux.just("你想了解哪件商品的详情？可以说商品名，我帮你找。");
            }
            List<ProductResolver.Hit> hits = productResolver.match(context.time(), keyword);
            if (hits.isEmpty()) {
                return Flux.just("没找到「" + keyword + "」这件商品，可以说得再具体一点。");
            }
            if (hits.size() > 1) {
                return Flux.just(askWhichOne(hits));
            }
            productId = hits.get(0).productId();
        }

        List<SeckillProductVo> products = seckillProductMapper.queryProductsByIds(List.of(productId));
        if (products == null || products.isEmpty()) {
            return Flux.just("没有查到这件商品的详情。");
        }
        return Flux.just(format(products));
    }

    private String format(List<SeckillProductVo> products) {
        StringBuilder sb = new StringBuilder();
        for (SeckillProductVo product : products) {
            String detail = product.getProductDetail();
            if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
                detail = detail.substring(0, MAX_DETAIL_LENGTH) + "...";
            }
            sb.append("【").append(product.getProductName()).append("】")
                    .append("价格 ").append(product.getProductPrice()).append(" 元\n")
                    .append("标题：").append(product.getProductTitle()).append("\n")
                    .append("详情：").append(detail).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String askWhichOne(List<ProductResolver.Hit> hits) {
        StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 件相关的商品，你要看哪一件？\n");
        for (ProductResolver.Hit hit : hits) {
            sb.append("- ").append(hit.name())
                    .append("（").append(hit.time()).append(" 点场次）\n");
        }
        return sb.toString();
    }
}

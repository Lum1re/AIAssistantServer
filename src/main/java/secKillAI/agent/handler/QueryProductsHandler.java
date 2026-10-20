package secKillAI.agent.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import secKillAI.agent.AgentContext;
import secKillAI.agent.Intent;
import secKillAI.agent.support.ProductResolver;

import java.util.List;

/**
 * 查场次商品（只读）。
 *
 * <p>输出里标注「秒杀商品ID」，并说明想了解详情或库存可以直接说商品名。
 */
@Component
@RequiredArgsConstructor
public class QueryProductsHandler implements IntentHandler {

    static final String SLOT_TIME = "time";

    /** 一次最多列 5 件，避免把上千字塞进上下文 */
    private static final int MAX_LIST = 5;

    private final ProductResolver productResolver;

    @Override
    public Intent intent() {
        return Intent.QUERY_PRODUCTS;
    }

    @Override
    public HandlerOutcome handle(AgentContext context) {
        Integer time = context.time();
        if (time == null) {
            // 没说是哪个场次，绝不默认 10 点 → 追问
            return HandlerOutcome.ask("请问是 10 点、12 点还是 14 点场次？", SLOT_TIME);
        }

        List<ProductResolver.Hit> hits = productResolver.listOf(time);
        if (hits.isEmpty()) {
            return HandlerOutcome.reply(time + " 点场次当前没有秒杀商品。");
        }

        StringBuilder sb = new StringBuilder()
                .append(time).append(" 点场次的秒杀商品共 ").append(hits.size()).append(" 件：\n");
        int shown = 0;
        for (ProductResolver.Hit hit : hits) {
            if (shown >= MAX_LIST) {
                break;
            }
            sb.append("- ").append(hit.name())
                    .append("，秒杀价 ").append(hit.product().getSeckillPrice()).append(" 元")
                    .append("，原价 ").append(hit.product().getProductPrice()).append(" 元")
                    .append("（秒杀商品ID ").append(hit.seckillId()).append("）\n");
            shown++;
        }
        if (hits.size() > MAX_LIST) {
            sb.append("……还有 ").append(hits.size() - MAX_LIST).append(" 件，需要我列全吗？");
        } else {
            sb.append("想了解哪一件的详情或库存，告诉我商品名即可。");
        }
        return HandlerOutcome.reply(sb.toString());
    }
}

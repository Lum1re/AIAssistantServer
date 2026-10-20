package secKillAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import secKillAI.web.service.IOrderOperateService;

@Slf4j
public class SeckillOrderTool {

    /** 下单失败时给模型看的话术。刻意不暴露技术细节。 */
    private static final String FAIL_REPLY = "下单失败了，请稍后再试。如果一直失败，可以联系人工客服。";

    private final IOrderOperateService orderOperateService;
    private final long userId;

    public SeckillOrderTool(IOrderOperateService orderOperateService, long userId) {
        this.orderOperateService = orderOperateService;
        this.userId = userId;
    }

    @Tool(name="下单",value = "帮当前用户秒杀下单。调用前必须先跟用户确认要买哪一件商品，"
            + "以及用户是否可以接受这个秒杀价。参数是场次和秒杀商品ID。")
    public String doSeckill(@P("秒杀场次，只能是10、12、14") int time,
                            @P("秒杀商品ID") Long seckillId) {
        try {
            return orderOperateService.doSeckill(userId, time, seckillId);
        } catch (Exception e) {
            // 业务层是故意抛异常的（为了让 @Transactional 回滚），这里必须接住：
            // 否则异常会一路抛到 LangChain4j，模型收到的是技术异常，甚至整个对话中断。
            log.warn("下单工具执行失败，userId={} time={} seckillId={}", userId, time, seckillId, e);
            return FAIL_REPLY;
        }
    }
}

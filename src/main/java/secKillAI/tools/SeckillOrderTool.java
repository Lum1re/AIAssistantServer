package secKillAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import secKillAI.web.service.IOrderOperateService;

public class SeckillOrderTool {

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
        return orderOperateService.doSeckill(userId, time, seckillId);
    }
}
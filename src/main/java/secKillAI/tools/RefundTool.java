package secKillAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import secKillAI.web.service.IOrderOperateService;

public class RefundTool {

    private final IOrderOperateService orderOperateService;
    private final long userId;

    public RefundTool(IOrderOperateService orderOperateService, long userId) {
        this.orderOperateService = orderOperateService;
        this.userId = userId;
    }

    @Tool(name = "退款前置条件", value = "第一步：准备退款。校验订单是否可以退款，并返回需要用户确认的信息。"
            + "调用这个之后必须等用户明确回复「确认」，才能调用 confirmRefund。")
    public String prepareRefund(@P("订单号") String orderNo) {
        return orderOperateService.prepareRefund(userId, orderNo);
    }

    @Tool(name = "确认退款", value = "第二步：执行退款。只有在用户明确表示同意退款之后才能调用，不需要传任何参数。")
    public String confirmRefund() {
        return orderOperateService.confirmRefund(userId);
    }
}
package secKillAI.agent.handler;

import secKillAI.agent.AgentContext;
import secKillAI.agent.confirm.ConfirmTicket;

/**
 * 能处理"用户确认后执行"的处理器。
 *
 * <p>和 {@link IntentHandler} 分开，是因为绝大多数处理器（4 个只读查询）根本不需要它。
 * 如果把它塞进 {@code IntentHandler} 里当默认方法，每个处理器都要面对一个用不上的接口。
 *
 * <p>为什么执行时不重新识别意图：
 * <b>待办是什么由票据决定，不由模型这一次的输出决定。</b>
 * 用户说"确认"时，模型能提供的信息为零，重新识别只会引入不确定性。
 */
public interface ConfirmableHandler extends IntentHandler {

    /**
     * 执行票据里描述的操作。
     *
     * @param context 本次请求的上下文（{@code slots} 来自票据的 payload，不是模型这次给的）
     * @param ticket  服务端暂存的票据；实现方可以再用它做二次校验
     * @return 执行结果的话术
     */
    HandlerOutcome execute(AgentContext context, ConfirmTicket ticket);
}
